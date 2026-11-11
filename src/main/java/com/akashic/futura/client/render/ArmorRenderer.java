package com.akashic.futura.client.render;

import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.ForgeHooksClient;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.akashic.futura.Futura3D;
import com.akashic.futura.armor.ArmorBoxes;
import com.akashic.futura.armor.ArmorFit;
import com.akashic.futura.client.CosmeticArmor;
import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.geo.Mat4;
import com.akashic.futura.geo.PoseMatrices;

/**
 * Worn armor on a player's model, where its {@link ArmorFit} puts it (every model: the default model, Steve and
 * Alex tuned by hand, the others from their bones), drawn as
 * {@code RenderPlayer.shouldRenderPass} and {@code RendererLivingEntity.doRender} draw the four armor passes: the
 * texture of {@code RenderBiped.getArmorResource} (Forge's {@code getArmorTexture} included), the dye colour and its
 * overlay, the enchantment glint.
 * <ul>
 * <li>Armor drawn with the vanilla biped: its boxes cut at the elbows, knees and ankles, each cut on its bone
 * ({@link ArmorBoxes}), so it bends with the model.</li>
 * <li>Armor with its own model ({@code Item.getArmorModel}, as Thaumcraft's fortress armor, robes and goggles): the
 * model runs its own {@code render} once with nothing written (it decides there which of its pieces show), then each
 * of its biped parts, with the pieces hung on it, is drawn whole on the bone of the part's first cut.</li>
 * </ul>
 * The armor shown is Cosmetic Armor Reworked's when it is there ({@link CosmeticArmor}).
 */
public final class ArmorRenderer {

    private static final ResourceLocation GLINT = new ResourceLocation("textures/misc/enchanted_item_glint.png");
    private static final float PIXEL = 1f / 16;

    private final ModelBiped chestplate = new ModelBiped(1.0F);
    private final ModelBiped leggings = new ModelBiped(0.5F);
    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
    private final float[] locator = new float[Mat4.FLOATS];
    private final float[] fit = new float[Mat4.FLOATS];
    private final float[] matrix = new float[Mat4.FLOATS];
    private final float[] pivot = new float[3];
    /** Per fit, per inflation (0.5 or 1 pixel), the quads of each cut, in the fit's order. */
    private final Map<ArmorFit, float[][][]> quads = new WeakHashMap<>();
    /** Items whose armor failed to draw: said once, then drawn no more. */
    private final Set<Item> failed = new HashSet<>();

    /**
     * Draws the armor a player shows on his model, in the model's frame (the current GL matrix is model space).
     *
     * @param pose the frame's bone matrices
     */
    public void render(EntityPlayer player, PoseMatrices pose, ArmorFit armor, float partialTicks) {
        for (int pass = 0; pass < 4; pass++) {
            ItemStack stack = CosmeticArmor.shown(player, 3 - pass);
            if (stack == null || !(stack.getItem() instanceof ItemArmor) || failed.contains(stack.getItem())) {
                continue;
            }
            try {
                renderPass(player, pose, armor, stack, pass, partialTicks);
            } catch (RuntimeException | LinkageError e) {
                failed.add(stack.getItem());
                Futura3D.LOG.error("Armor {} could not be drawn on the model; it is left out", stack.getItem(), e);
            }
        }
    }

    private void renderPass(EntityPlayer player, PoseMatrices pose, ArmorFit armor, ItemStack stack, int pass,
        float partialTicks) {
        ItemArmor item = (ItemArmor) stack.getItem();
        ModelBiped base = pass == 2 ? leggings : chestplate;
        base.bipedHead.showModel = pass == 0;
        base.bipedHeadwear.showModel = pass == 0;
        base.bipedBody.showModel = pass == 1 || pass == 2;
        base.bipedRightArm.showModel = pass == 1;
        base.bipedLeftArm.showModel = pass == 1;
        base.bipedRightLeg.showModel = pass == 2 || pass == 3;
        base.bipedLeftLeg.showModel = pass == 2 || pass == 3;
        ModelBiped model = ForgeHooksClient.getArmorModel(player, stack, pass, base);
        boolean own = model != null && model != base;
        bind(RenderBiped.getArmorResource(player, stack, pass, null));
        int color = item.getColor(stack);
        if (color != -1) {
            GL11.glColor3f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f);
        } else {
            GL11.glColor3f(1, 1, 1);
        }
        if (own) {
            prepare(model, player, partialTicks);
        }
        draw(pose, armor, pass, own ? model : null);
        if (color != -1) {
            bind(RenderBiped.getArmorResource(player, stack, pass, "overlay"));
            GL11.glColor3f(1, 1, 1);
            draw(pose, armor, pass, own ? model : null);
        }
        if (stack.isItemEnchanted()) {
            glint(player, pose, armor, pass, own ? model : null, partialTicks);
        }
        GL11.glColor4f(1, 1, 1, 1);
    }

    /** The biped armor's cuts, or the parts of an armor's own model ({@code own}), at their bones. */
    private void draw(PoseMatrices pose, ArmorFit armor, int pass, ModelBiped own) {
        if (own != null) {
            drawOwn(pose, armor, own);
            return;
        }
        float[][] cuts = quads(armor)[pass == 2 ? 0 : 1];
        BakedGeometry geometry = pose.geometry();
        Tessellator t = Tessellator.instance;
        for (int i = 0; i < armor.pieces.size(); i++) {
            ArmorFit.Piece piece = armor.pieces.get(i);
            if (!piece.part.shownIn(pass) || !placeAt(pose, geometry, piece)) {
                continue;
            }
            GL11.glPushMatrix();
            multiply(matrix);
            float[] q = cuts[i];
            t.startDrawingQuads();
            for (int o = 0; o < q.length; o += ArmorBoxes.QUAD_FLOATS) {
                t.setNormal(q[o + 20], q[o + 21], q[o + 22]);
                for (int k = 0; k < 4; k++) {
                    int c = o + k * 5;
                    t.addVertexWithUV(q[c], q[c + 1], q[c + 2], q[c + 3], q[c + 4]);
                }
            }
            t.draw();
            GL11.glPopMatrix();
        }
    }

    private void drawOwn(PoseMatrices pose, ArmorFit armor, ModelBiped model) {
        BakedGeometry geometry = pose.geometry();
        drawPart(pose, geometry, armor, ArmorFit.Part.HEAD, model.bipedHead);
        drawPart(pose, geometry, armor, ArmorFit.Part.HEADWEAR, model.bipedHeadwear);
        drawPart(pose, geometry, armor, ArmorFit.Part.BODY, model.bipedBody);
        drawPart(pose, geometry, armor, ArmorFit.Part.RIGHT_ARM, model.bipedRightArm);
        drawPart(pose, geometry, armor, ArmorFit.Part.LEFT_ARM, model.bipedLeftArm);
        drawPart(pose, geometry, armor, ArmorFit.Part.RIGHT_LEG, model.bipedRightLeg);
        drawPart(pose, geometry, armor, ArmorFit.Part.LEFT_LEG, model.bipedLeftLeg);
    }

    /** One part of an armor's own model, with what hangs on it, whole on its bone; its own pose is set aside. */
    private void drawPart(PoseMatrices pose, BakedGeometry geometry, ArmorFit armor, ArmorFit.Part part,
        ModelRenderer renderer) {
        ArmorFit.Piece piece = armor.whole(part);
        if (renderer == null || !renderer.showModel || renderer.isHidden || piece == null) {
            return;
        }
        if (!placeAt(pose, geometry, piece)) {
            return;
        }
        // ModelRenderer.render scales its pixels itself
        Mat4.scale(matrix, 0, 16, 16, 16);
        float rx = renderer.rotateAngleX;
        float ry = renderer.rotateAngleY;
        float rz = renderer.rotateAngleZ;
        float px = renderer.rotationPointX;
        float py = renderer.rotationPointY;
        float pz = renderer.rotationPointZ;
        float ox = renderer.offsetX;
        float oy = renderer.offsetY;
        float oz = renderer.offsetZ;
        renderer.rotateAngleX = renderer.rotateAngleY = renderer.rotateAngleZ = 0;
        renderer.rotationPointX = renderer.rotationPointY = renderer.rotationPointZ = 0;
        renderer.offsetX = renderer.offsetY = renderer.offsetZ = 0;
        GL11.glPushMatrix();
        try {
            multiply(matrix);
            renderer.render(PIXEL);
        } finally {
            GL11.glPopMatrix();
            renderer.rotateAngleX = rx;
            renderer.rotateAngleY = ry;
            renderer.rotateAngleZ = rz;
            renderer.rotationPointX = px;
            renderer.rotationPointY = py;
            renderer.rotationPointZ = pz;
            renderer.offsetX = ox;
            renderer.offsetY = oy;
            renderer.offsetZ = oz;
        }
    }

    /**
     * {@link #matrix}: the bone's locator frame followed by the piece's fit; false if the bone is missing or hidden.
     */
    private boolean placeAt(PoseMatrices pose, BakedGeometry geometry, ArmorFit.Piece piece) {
        int bone = geometry.indexOf(piece.bone);
        if (bone < 0 || !pose.visible(bone)) {
            return false;
        }
        float[] p = geometry.bones[bone].pivot;
        // blocks with x negated to the file's pixels
        pivot[0] = -p[0] * 16;
        pivot[1] = p[1] * 16;
        pivot[2] = p[2] * 16;
        pose.locator(bone, locator, 0);
        ArmorFit.matrix(piece, pivot, fit);
        Mat4.mul(locator, 0, fit, 0, matrix, 0);
        return true;
    }

    /**
     * Runs an armor's own model once without writing anything, as the vanilla pass would before drawing: models such
     * as Thaumcraft's fortress armor choose their visible pieces (mask, goggles, the set) inside {@code render}, and
     * pose the pieces hung on the parts in {@code setRotationAngles}.
     */
    private static void prepare(ModelBiped model, EntityPlayer player, float pt) {
        ItemStack held = player.getHeldItem();
        model.heldItemRight = held != null ? 1 : 0;
        model.aimedBow = false;
        if (held != null && player.getItemInUseCount() > 0) {
            EnumAction action = held.getItemUseAction();
            if (action == EnumAction.block) {
                model.heldItemRight = 3;
            } else if (action == EnumAction.bow) {
                model.aimedBow = true;
            }
        }
        model.isSneak = player.isSneaking();
        model.isRiding = player.isRiding();
        model.isChild = false;
        model.onGround = player.getSwingProgress(pt);
        float swing = player.limbSwing - player.limbSwingAmount * (1 - pt);
        float amount = player.prevLimbSwingAmount + (player.limbSwingAmount - player.prevLimbSwingAmount) * pt;
        float yaw = player.prevRotationYawHead + (player.rotationYawHead - player.prevRotationYawHead) * pt;
        float body = player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * pt;
        float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * pt;
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            model.setLivingAnimations(player, swing, amount, pt);
            model.render(player, swing, amount, player.ticksExisted + pt, yaw - body, pitch, PIXEL);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** {@code RendererLivingEntity.doRender}'s enchantment glint over the pass just drawn. */
    private void glint(EntityPlayer player, PoseMatrices pose, ArmorFit armor, int pass, ModelBiped own, float pt) {
        float time = player.ticksExisted + pt;
        bind(GLINT);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDepthFunc(GL11.GL_EQUAL);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_LIGHTING);
        try {
            for (int k = 0; k < 2; k++) {
                float f = 0.76f;
                GL11.glColor4f(0.5f * f, 0.25f * f, 0.8f * f, 1);
                GL11.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
                GL11.glMatrixMode(GL11.GL_TEXTURE);
                GL11.glLoadIdentity();
                float scroll = time * (0.001f + k * 0.003f) * 20;
                float s = 1 / 3f;
                GL11.glScalef(s, s, s);
                GL11.glRotatef(30 - k * 60, 0, 0, 1);
                GL11.glTranslatef(0, scroll, 0);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                draw(pose, armor, pass, own);
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_BLEND);
            if (lighting) {
                GL11.glEnable(GL11.GL_LIGHTING);
            }
        }
    }

    private float[][][] quads(ArmorFit armor) {
        float[][][] q = quads.get(armor);
        if (q == null) {
            q = new float[2][armor.pieces.size()][];
            for (int i = 0; i < armor.pieces.size(); i++) {
                ArmorFit.Piece piece = armor.pieces.get(i);
                // the leggings' model grows half a pixel, the others one
                q[0][i] = ArmorBoxes.quads(piece, 0.5f);
                q[1][i] = ArmorBoxes.quads(piece, 1.0f);
            }
            quads.put(armor, q);
        }
        return q;
    }

    private void multiply(float[] m) {
        buffer.clear();
        buffer.put(m, 0, 16);
        buffer.flip();
        GL11.glMultMatrix(buffer);
    }

    private static void bind(ResourceLocation texture) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
    }
}

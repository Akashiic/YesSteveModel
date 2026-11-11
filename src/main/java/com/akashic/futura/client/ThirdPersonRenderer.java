/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the draw order of src/main/java/com/elfmcys/ysm/geckolib3/geo/GeoReplacedEntityRenderer.java (renderPrepared,
 * setupRotations) and the layers of
 * src/main/java/com/elfmcys/ysm/client/renderer/layer/CustomPlayerItemInHandLayer.java
 * and CustomPlayerHeadLayer.java, Apache License 2.0.
 * Changes: 1.7.10 placement (RenderPlayer.renderLivingAt/rotateCorpse in a bed) and GL immediate state instead of
 * render types.
 */
package com.akashic.futura.client;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.akashic.futura.anim.BoneAttributes;
import com.akashic.futura.anim.BonePose;
import com.akashic.futura.armor.ArmorFit;
import com.akashic.futura.client.render.ArmorRenderer;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.client.render.HeldItemRenderer;
import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.geo.Mat4;
import com.akashic.futura.geo.PoseMatrices;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The third-person draw of one player, after its animation is updated: placement (standing or in a bed), the model
 * with its bone attributes, the items at the hand and head locators, the hurt tint, and the locator frames kept for
 * {@link PlayerModels#locator}. Follows upstream's {@code GeoReplacedEntityRenderer.renderPrepared}: no death tilt
 * (the {@code death} animation does it), invisible players draw nothing, items and other layers after the model unless
 * the model asks for {@code render_layers_first}.
 */
public final class ThirdPersonRenderer {

    static final String RIGHT_HAND = "RightHandLocator";
    static final String LEFT_HAND = "LeftHandLocator";
    static final String HEAD = "Head";
    static final String ELYTRA = "ElytraLocator";
    /** Models without an {@code ElytraLocator}: the wings on this bone, at the default model's locator. */
    static final String ELYTRA_FALLBACK = "UpBody";
    /** The default model's {@code ElytraLocator} from its {@code UpBody} pivot, in blocks (0, 11, 2 pixels). */
    static final float[] ELYTRA_FALLBACK_OFFSET = { 0, 11f / 16, 2f / 16 };

    /** What one player looked like in its last draw. */
    private static final class Frame {

        final PoseMatrices pose;
        /** The geometry the pose is for: a player whose model changes gets a new frame. */
        final BakedGeometry geometry;
        /** Model space to camera-relative render space (scale included). */
        final float[] entity = new float[Mat4.FLOATS];
        /** The render position of the feet in that draw (relative to the camera of that frame). */
        double x, y, z;
        long frame = -10;

        Frame(GeometryRenderer renderer) {
            geometry = renderer.geometry();
            pose = new PoseMatrices(geometry);
        }
    }

    private final Map<AbstractClientPlayer, Frame> frames = new WeakHashMap<>();
    private final HeldItemRenderer items = new HeldItemRenderer();
    private final ArmorRenderer armorRenderer = new ArmorRenderer();
    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
    private final float[] locator = new float[Mat4.FLOATS];
    private Frame guiFrame;
    private long frameCount;
    private float partialTicks;
    /** The player of the last draw (world or screen) and its frame, for {@code RenderPlayerEvent.Post} handlers. */
    /** Weak: compared by identity only, it must not keep a player (and his world) after he leaves. */
    private java.lang.ref.WeakReference<EntityPlayer> lastPlayer;
    private Frame lastFrame;
    private long lastFrameCount = -10;

    ThirdPersonRenderer() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            frameCount++;
            partialTicks = event.renderTickTime;
        }
    }

    /** Forgets every frame. */
    void reset() {
        frames.clear();
        guiFrame = null;
        forgetLastDraw();
    }

    /** Forgets the frames of models that are no longer drawn (freed with them). */
    void forgetReleased() {
        reset();
    }

    /** A player is about to be drawn: until the model draws him, the last draw is not a model's. */
    void forgetLastDraw() {
        lastPlayer = null;
        lastFrame = null;
    }

    private Frame frame(AbstractClientPlayer player, GeometryRenderer renderer, boolean gui) {
        if (gui) {
            // Inventory and other screens: drawn, but not kept as the player's frame in the world.
            if (guiFrame == null || guiFrame.geometry != renderer.geometry()) {
                guiFrame = new Frame(renderer);
            }
            return guiFrame;
        }
        Frame frame = frames.get(player);
        if (frame == null || frame.geometry != renderer.geometry()) {
            frame = new Frame(renderer);
            frames.put(player, frame);
        }
        return frame;
    }

    /**
     * @param x        render position of the feet, relative to the camera
     * @param bodyYaw  the animator's body yaw
     * @param pose     the frame's animation, or {@code null} for the rest pose
     * @param attrs    bone attributes, or {@code null}
     * @param gui      drawn in a screen (the inventory): the locator frames of the world draw are kept
     * @param armor    where the worn armor goes on this model, or {@code null} (a model without armor)
     * @param ownWings the model draws its own wings for the elytra ({@code ysm.has_elytra}): no wings on its
     *                 {@code UpBody} when it has no {@code ElytraLocator}
     */
    void render(AbstractClientPlayer player, GeometryRenderer renderer, ResourceLocation texture, float widthScale,
        float heightScale, boolean layersFirst, BonePose pose, BoneAttributes attrs, double x, double y, double z,
        float bodyYaw, float pt, boolean gui, ArmorFit armor, boolean ownWings) {
        Frame frame = frame(player, renderer, gui);
        frame.pose.compute(pose);
        placement(player, x, y, z, bodyYaw, widthScale, heightScale, frame.entity);
        frame.x = x;
        frame.y = y;
        frame.z = z;
        if (!gui) {
            frame.frame = frameCount;
        }
        lastPlayer = new java.lang.ref.WeakReference<>(player);
        lastFrame = frame;
        lastFrameCount = frameCount;
        if (player.isInvisible()) {
            // Upstream draws neither the body nor its layers (only a glowing outline, which 1.7.10 lacks).
            return;
        }
        GL11.glPushMatrix();
        multiply(frame.entity, 0);
        if (layersFirst) {
            layers(player, frame.pose, armor, ownWings, pt);
        }
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
        renderer.draw(frame.pose, attrs, 1, 1, 1, 1);
        if (!layersFirst) {
            layers(player, frame.pose, armor, ownWings, pt);
        }
        if (player.hurtTime > 0 || player.deathTime > 0) {
            hurtTint(player, renderer, frame.pose, pt);
        }
        GL11.glPopMatrix();
    }

    /**
     * Model space to camera-relative render space: the feet at the render position turned to the body yaw, or, in a
     * bed, vanilla's {@code RenderPlayer.renderLivingAt} offset and {@code rotateCorpse} turn (upstream keeps the
     * vanilla bed turn and lets the {@code sleep} animation pose the body); then the model's scale. Gliding has no turn
     * here: as upstream (whose {@code setupRotations} is the living entity's, without the player's glide tilt), the
     * model's {@code elytra_fly} lays the body down itself.
     */
    static void placement(EntityPlayer player, double x, double y, double z, float bodyYaw, float widthScale,
        float heightScale, float[] out) {
        Mat4.identity(out, 0);
        if (player.isEntityAlive() && player.isPlayerSleeping()) {
            Mat4.translate(
                out,
                0,
                (float) (x + player.field_71079_bU),
                (float) (y + player.field_71082_cx),
                (float) (z + player.field_71089_bV));
            Mat4.rotateY(out, 0, (float) Math.toRadians(player.getBedOrientationInDegrees()));
            Mat4.rotateZ(out, 0, (float) Math.toRadians(90));
            Mat4.rotateY(out, 0, (float) Math.toRadians(270));
        } else {
            Mat4.translate(out, 0, (float) x, (float) y, (float) z);
            Mat4.rotateY(out, 0, (float) Math.toRadians(180 - bodyYaw));
        }
        Mat4.scale(out, 0, widthScale, heightScale, widthScale);
    }

    private void multiply(float[] m, int offset) {
        buffer.clear();
        buffer.put(m, offset, 16);
        buffer.flip();
        GL11.glMultMatrix(buffer);
    }

    /**
     * Et Futurum Requiem's wings at the {@code ElytraLocator} group, as upstream's {@code CustomPlayerElytraLayer}
     * (a hidden locator hides them); a model without the locator gets them on its {@code UpBody}, where the default
     * model has it, unless it draws wings of its own for the elytra ({@code ysm.has_elytra}, as
     * {@code wine_fox/21_saint}): upstream draws nothing there either.
     */
    private void wings(AbstractClientPlayer player, PoseMatrices pose, boolean ownWings) {
        if (pose.group(ELYTRA).length > 0) {
            for (int bone : pose.visibleGroup(ELYTRA)) {
                pose.locator(bone, locator, 0);
                wingsAt(player);
            }
            return;
        }
        if (ownWings) {
            return;
        }
        int[] body = pose.visibleGroup(ELYTRA_FALLBACK);
        if (body.length > 0) {
            pose.locator(body[0], locator, 0);
            Mat4.translate(locator, 0, ELYTRA_FALLBACK_OFFSET[0], ELYTRA_FALLBACK_OFFSET[1], ELYTRA_FALLBACK_OFFSET[2]);
            wingsAt(player);
        }
    }

    private void wingsAt(AbstractClientPlayer player) {
        GL11.glPushMatrix();
        try {
            multiply(locator, 0);
            // the locator (blocks, y up, the front toward -z, at the back) to the biped body frame EFR's layer draws
            // in (y down and x mirrored, as RendererLivingEntity's scale(-1, -1, 1)); EFR moves the wings 2 pixels back
            // from the neck to the back, where the locator already is
            GL11.glRotatef(180, 0, 0, 1);
            GL11.glTranslatef(0, 0, -0.125f);
            ElytraBridge.drawWings(player, partialTicks);
        } finally {
            GL11.glPopMatrix();
        }
    }

    /** The worn armor (models with an {@link ArmorFit}) and the items at the locators, in the model frame. */
    private void layers(AbstractClientPlayer player, PoseMatrices pose, ArmorFit armor, boolean ownWings, float pt) {
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glColor4f(1, 1, 1, 1);
        try {
            if (armor != null) {
                armorRenderer.render(player, pose, armor, pt);
            }
            ItemStack held = player.getHeldItem();
            if (held != null) {
                for (int bone : pose.visibleGroup(RIGHT_HAND)) {
                    pose.locator(bone, locator, 0);
                    items.renderHeld(player, held, locator, 0);
                }
            }
            ItemStack offhand = PlayerModels.offhand(player);
            if (offhand != null) {
                for (int bone : pose.visibleGroup(LEFT_HAND)) {
                    pose.locator(bone, locator, 0);
                    items.renderHeld(player, offhand, locator, 0);
                }
            }
            if (ElytraBridge.elytra(player) != null) {
                wings(player, pose, ownWings);
            }
            ItemStack helmet = CosmeticArmor.shown(player, 3);
            if (HeldItemRenderer.drawsOnHead(helmet)) {
                for (int bone : pose.visibleGroup(HEAD)) {
                    pose.locator(bone, locator, 0);
                    items.renderHead(player, helmet, locator, 0);
                }
            }
        } finally {
            GL11.glPopAttrib();
        }
    }

    /**
     * {@code RendererLivingEntity.doRender}'s red overlay: the model again, untextured, where its depth was written.
     */
    private static void hurtTint(AbstractClientPlayer player, GeometryRenderer renderer, PoseMatrices pose, float pt) {
        float brightness = player.getBrightness(pt);
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthFunc(GL11.GL_EQUAL);
        GL11.glColor4f(brightness, 0, 0, 0.4f);
        renderer.drawFlat(pose);
        GL11.glPopAttrib();
    }

    /**
     * The first visible bone of a locator group in a player's last draw, in camera-relative render space.
     *
     * @return a new column-major {@code float[16]}, or {@code null}
     */
    float[] locator(EntityPlayer player, String name) {
        float[][] all = locators(player, name);
        return all == null || all.length == 0 ? null : all[0];
    }

    float[][] locators(EntityPlayer player, String name) {
        Frame frame = worldFrame(player);
        if (frame == null) {
            return null;
        }
        float[][] out = locators(frame, name);
        if (frame.frame != frameCount) {
            // Drawn in the frame before (this caller runs before the player is drawn): keep the frame on the player
            // as he is in this frame, relative to this frame's camera.
            double[] now = renderPosition(player, partialTicks);
            float dx = (float) (now[0] - frame.x);
            float dy = (float) (now[1] - frame.y);
            float dz = (float) (now[2] - frame.z);
            for (float[] m : out) {
                m[12] += dx;
                m[13] += dy;
                m[14] += dz;
            }
        }
        return out;
    }

    private static float[][] locators(Frame frame, String name) {
        int[] bones = frame.pose.visibleGroup(name);
        float[][] out = new float[bones.length][];
        float[] local = new float[Mat4.FLOATS];
        for (int i = 0; i < bones.length; i++) {
            frame.pose.locator(bones[i], local, 0);
            out[i] = new float[Mat4.FLOATS];
            Mat4.mul(frame.entity, 0, local, 0, out[i], 0);
        }
        return out;
    }

    /** The player's world draw of this frame or the one before, or null. */
    private Frame worldFrame(EntityPlayer player) {
        Frame frame = frames.get(player);
        return frame == null || frameCount - frame.frame > 1 ? null : frame;
    }

    /** Whether the player was drawn in the world with a model in this frame or the one before. */
    boolean modelled(EntityPlayer player) {
        return worldFrame(player) != null;
    }

    /** Whether the player's model has a bone of that locator group, visible or not (false if never drawn). */
    boolean hasGroup(EntityPlayer player, String name) {
        Frame frame = frames.get(player);
        return frame != null && frame.pose.group(name).length > 0;
    }

    /** Whether the player draw that just ended (world or screen) was this player's model. */
    boolean justDrew(EntityPlayer player) {
        return player != null && lastPlayer != null && lastPlayer.get() == player && lastFrameCount == frameCount;
    }

    /**
     * The visible bones of a locator group in the draw that just ended, relative to the matrix the player renderer
     * was called with (camera-relative render space in the world, the screen's space in the inventory); null if that
     * draw was not this player's model.
     */
    float[][] locatorsOfLastDraw(EntityPlayer player, String name) {
        return justDrew(player) ? locators(lastFrame, name) : null;
    }

    /** {@code RendererLivingEntity.doRender}'s position of an entity: interpolated, relative to the camera. */
    private static double[] renderPosition(EntityPlayer p, float pt) {
        return new double[] { p.lastTickPosX + (p.posX - p.lastTickPosX) * pt - RenderManager.renderPosX,
            p.lastTickPosY + (p.posY - p.lastTickPosY) * pt - RenderManager.renderPosY,
            p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * pt - RenderManager.renderPosZ };
    }
}

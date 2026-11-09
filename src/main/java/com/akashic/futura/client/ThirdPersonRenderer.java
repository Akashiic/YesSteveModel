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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.akashic.futura.anim.BoneAttributes;
import com.akashic.futura.anim.BonePose;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.client.render.HeldItemRenderer;
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

    /** What one player looked like in its last draw. */
    private static final class Frame {

        final PoseMatrices pose;
        /** Model space to camera-relative render space (scale included). */
        final float[] entity = new float[Mat4.FLOATS];
        long frame = -10;

        Frame(GeometryRenderer renderer) {
            pose = new PoseMatrices(renderer.geometry());
        }
    }

    private final Map<AbstractClientPlayer, Frame> frames = new WeakHashMap<>();
    private final HeldItemRenderer items = new HeldItemRenderer();
    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
    private final float[] locator = new float[Mat4.FLOATS];
    private GeometryRenderer framesFor;
    private Frame guiFrame;
    private long frameCount;

    ThirdPersonRenderer() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            frameCount++;
        }
    }

    /** Forgets every frame (the model changed). */
    void reset() {
        frames.clear();
        guiFrame = null;
        framesFor = null;
    }

    private Frame frame(AbstractClientPlayer player, GeometryRenderer renderer, boolean gui) {
        if (framesFor != renderer) {
            frames.clear();
            guiFrame = null;
            framesFor = renderer;
        }
        if (gui) {
            // Inventory and other screens: drawn, but not kept as the player's frame in the world.
            if (guiFrame == null) {
                guiFrame = new Frame(renderer);
            }
            return guiFrame;
        }
        Frame frame = frames.get(player);
        if (frame == null) {
            frame = new Frame(renderer);
            frames.put(player, frame);
        }
        return frame;
    }

    /**
     * @param x       render position of the feet, relative to the camera
     * @param bodyYaw the animator's body yaw
     * @param pose    the frame's animation, or {@code null} for the rest pose
     * @param attrs   bone attributes, or {@code null}
     * @param gui     drawn in a screen (the inventory): the locator frames of the world draw are kept
     */
    void render(AbstractClientPlayer player, GeometryRenderer renderer, ResourceLocation texture, float widthScale,
        float heightScale, boolean layersFirst, BonePose pose, BoneAttributes attrs, double x, double y, double z,
        float bodyYaw, float pt, boolean gui) {
        Frame frame = frame(player, renderer, gui);
        frame.pose.compute(pose);
        placement(player, x, y, z, bodyYaw, widthScale, heightScale, frame.entity);
        if (!gui) {
            frame.frame = frameCount;
        }
        if (player.isInvisible()) {
            // Upstream draws neither the body nor its layers (only a glowing outline, which 1.7.10 lacks).
            return;
        }
        GL11.glPushMatrix();
        multiply(frame.entity, 0);
        if (layersFirst) {
            layers(player, frame.pose);
        }
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
        renderer.draw(frame.pose, attrs, 1, 1, 1, 1);
        if (!layersFirst) {
            layers(player, frame.pose);
        }
        if (player.hurtTime > 0 || player.deathTime > 0) {
            hurtTint(player, renderer, frame.pose, pt);
        }
        GL11.glPopMatrix();
    }

    /**
     * Model space to camera-relative render space: the feet at the render position turned to the body yaw, or, in a
     * bed, vanilla's {@code RenderPlayer.renderLivingAt} offset and {@code rotateCorpse} turn (upstream keeps the
     * vanilla bed turn and lets the {@code sleep} animation pose the body); then the model's scale.
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

    /** Items at the locators, in the model frame. */
    private void layers(AbstractClientPlayer player, PoseMatrices pose) {
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glColor4f(1, 1, 1, 1);
        try {
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
            ItemStack helmet = player.inventory.armorItemInSlot(3);
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
        Frame frame = frames.get(player);
        if (frame == null || frameCount - frame.frame > 1) {
            return null;
        }
        int[] bones = frame.pose.visibleGroup(name);
        float[][] out = new float[bones.length][];
        for (int i = 0; i < bones.length; i++) {
            frame.pose.locator(bones[i], locator, 0);
            out[i] = new float[Mat4.FLOATS];
            Mat4.mul(frame.entity, 0, locator, 0, out[i], 0);
        }
        return out;
    }
}

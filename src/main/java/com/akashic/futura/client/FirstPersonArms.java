/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/renderer/CustomFirstPersonArmRenderer.java (the arm placement) and
 * src/main/java/com/elfmcys/ysm/client/event/RenderFirstPlayerBackground.java (the Background part), Apache License
 * 2.0.
 * Changes: called from mixins on the 1.7.10 first-person hand code instead of Forge's RenderArmEvent and
 * RenderHandEvent; 1.7.10 draws only the right arm (also twice for maps), so only RightArm replaces it; display lists
 * instead of the native renderer; no SpecialPlayerRenderEvent texture override.
 */
package com.akashic.futura.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.BonePose;
import com.akashic.futura.anim.ModelAnimations;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.model.LoadedModel;

/**
 * The local player's first-person arms: the model's {@code arm} geometry, animated by its own
 * {@link PlayerAnimator#firstPerson first-person animator}, drawn where vanilla draws the bare right arm (vanilla's
 * bobbing, equip and swing transforms still move it). Held items keep vanilla's first-person placement, as upstream
 * (the arm is only drawn with an empty hand or a map). An optional {@code Background} bone is drawn in its rest pose in
 * front of the camera.
 */
public final class FirstPersonArms {

    public static final FirstPersonArms INSTANCE = new FirstPersonArms();

    private final PlayerSnapshot snapshot = new PlayerSnapshot();
    private LoadedModel builtFor;
    private ModelAnimations arms;
    private GeometryRenderer renderer;
    private PlayerAnimator animator;
    private PlayerAnimator body;
    private boolean failed;
    private boolean animationFailed;
    private float partialTicks;
    private boolean enabled = true;

    private FirstPersonArms() {}

    /** Turns the model's arms off (vanilla arms) without turning off the third-person model. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Start of {@code ItemRenderer.renderItemInFirstPerson}: remembers the frame's partial tick. */
    public void beginFrame(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    /**
     * In place of {@code RenderPlayer.renderFirstPersonArm}: draws the model's {@code RightArm} subtree at the
     * current matrix.
     *
     * @return false to let vanilla draw its arm
     */
    public boolean renderRightArm(EntityPlayer player) {
        if (!prepare(player) || arms.rightArm < 0) {
            return false;
        }
        BonePose pose = update((AbstractClientPlayer) player);
        GL11.glPushMatrix();
        // upstream CustomFirstPersonArmRenderer: LEFT_ARM ? 0.25 : -0.25, 1.8, 0; then flip into model space
        GL11.glTranslatef(-0.25f, 1.8f, 0);
        GL11.glScalef(-1, -1, 1);
        draw(arms.rightArm, pose);
        GL11.glPopMatrix();
        return true;
    }

    /**
     * Upstream {@code RenderFirstPlayerBackground}: the {@code Background} bone in its rest pose, at the hand pass's
     * matrix (after the arm sway), with the view bobbing undone and 1.5 blocks down. Nothing if the model has none.
     */
    public void renderBackground(EntityPlayer player) {
        if (!prepare(player) || arms.background < 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushMatrix();
        if (mc.gameSettings.viewBobbing) {
            unbob(player, partialTicks);
        }
        GL11.glTranslatef(0, -1.5f, 0);
        draw(arms.background, null);
        GL11.glPopMatrix();
    }

    /** Upstream's {@code bobView} of the background (the camera bob reversed), with the 1.7.10 player fields. */
    private static void unbob(EntityPlayer player, float pt) {
        float walk = player.distanceWalkedModified - player.prevDistanceWalkedModified;
        float phase = -(player.distanceWalkedModified + walk * pt);
        float bob = player.prevCameraYaw + (player.cameraYaw - player.prevCameraYaw) * pt;
        float pi = (float) Math.PI;
        GL11.glTranslatef(-MathHelper.sin(phase * pi) * bob * 0.5f, Math.abs(MathHelper.cos(phase * pi) * bob), 0);
        GL11.glRotatef(-MathHelper.sin(phase * pi) * bob * 3, 0, 0, 1);
        GL11.glRotatef(-Math.abs(MathHelper.cos(phase * pi - 0.2f) * bob) * 5, 1, 0, 0);
    }

    private boolean prepare(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        PlayerModels models = PlayerModels.INSTANCE;
        if (!enabled || !models.active() || player != mc.thePlayer || !(player instanceof AbstractClientPlayer)) {
            return false;
        }
        LoadedModel model = models.model();
        if (model != builtFor) {
            rebuild(model);
        }
        if (failed || arms == null) {
            return false;
        }
        PlayerAnimator current = models.animator((AbstractClientPlayer) player);
        if (current != body || animator == null) {
            body = current;
            animator = PlayerAnimator.firstPerson(arms, body);
        }
        return true;
    }

    private void rebuild(LoadedModel model) {
        if (renderer != null) {
            renderer.delete();
        }
        renderer = null;
        arms = null;
        animator = null;
        body = null;
        builtFor = model;
        failed = false;
        animationFailed = false;
        try {
            arms = ModelAnimations.compileFirstPerson(model);
            renderer = new GeometryRenderer(model.arm);
        } catch (RuntimeException e) {
            failed = true;
            Futura3D.LOG.error("Could not prepare the first-person arms of model {}", model.name(), e);
        }
    }

    private BonePose update(AbstractClientPlayer player) {
        float pt = partialTicks;
        PlayerInputsAdapter.fill(snapshot, player, pt);
        try {
            animator.update(snapshot, player.ticksExisted + pt);
            return animator.pose();
        } catch (RuntimeException e) {
            if (!animationFailed) {
                animationFailed = true;
                Futura3D.LOG.error("First-person animation failed for model {}", builtFor.name(), e);
            }
            return null;
        }
    }

    private void draw(int root, BonePose pose) {
        ResourceLocation texture = PlayerModels.INSTANCE.texture();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1, 1, 1, 1);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
        renderer.drawSubtree(root, pose);
        GL11.glPopAttrib();
    }
}

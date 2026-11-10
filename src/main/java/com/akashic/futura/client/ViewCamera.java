package com.akashic.futura.client;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.BonePose;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.geo.EyeHeight;
import com.akashic.futura.geo.Mat4;
import com.akashic.futura.geo.PoseMatrices;

/**
 * The first-person camera height from the local player's model ({@link EyeHeight}): at the end of
 * {@code EntityRenderer.orientCamera} the view is moved up or down so the eye sits over the model's
 * {@code ViewLocator} (or {@code Head}) in its current pose. Render only: the player's eye height for reach, block
 * picking and the server stays vanilla's, as upstream never changes it either.
 *
 * <p>
 * The body is not drawn in first person, so its animator is updated here each frame, as the third-person draw does
 * (same animator, same time base: switching views does not jump). Vanilla's height stays in third person, while
 * riding, dead, viewing through another entity, while the chosen model loads, without a {@code ViewLocator} or
 * {@code Head} bone, and with the {@code view_locator_camera} client option off.
 */
public final class ViewCamera {

    public static final ViewCamera INSTANCE = new ViewCamera();

    private final EyeHeight.Smoother smoother = new EyeHeight.Smoother();
    private final PlayerSnapshot snapshot = new PlayerSnapshot();
    private final float[] placement = new float[Mat4.FLOATS];
    private final float[] scratch = new float[Mat4.FLOATS];
    private PoseMatrices pose;
    private long lastNanos = -1;
    /** The last target, for the harness and logs; NaN when vanilla. */
    private float target = Float.NaN;

    private ViewCamera() {}

    /**
     * End of {@code EntityRenderer.orientCamera}: after vanilla's rotations and its final translation, so this
     * translation is along the world's vertical.
     */
    public void afterOrientCamera(float partialTicks) {
        float offset = update(partialTicks);
        if (offset != 0) {
            GL11.glTranslatef(0, -offset, 0);
        }
    }

    /** The camera's move from vanilla's height applied in the last frame, in blocks. */
    public float offset() {
        return smoother.value();
    }

    /** The unsmoothed move wanted in the last frame, or NaN when vanilla's height applies. */
    public float target() {
        return target;
    }

    private float update(float pt) {
        long now = System.nanoTime();
        float dt = lastNanos < 0 ? 0 : (now - lastNanos) / 1e9f;
        lastNanos = now;
        try {
            target = target(pt);
        } catch (RuntimeException e) {
            target = Float.NaN;
        }
        if (Float.isNaN(target)) {
            smoother.reset();
            return 0;
        }
        return smoother.update(target, dt);
    }

    private float target(float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.renderViewEntity != player
            || mc.gameSettings.thirdPersonView != 0
            || player.ridingEntity != null
            || !player.isEntityAlive()
            || ElytraBridge.flying(player) // gliding the body lies along the look: the head is no eye height
            || !ClientOptions.INSTANCE.viewLocatorCamera()) {
            return Float.NaN;
        }
        PlayerModels models = PlayerModels.INSTANCE;
        if (!models.active()) {
            return Float.NaN;
        }
        ClientModel model = models.readyModelOf(player);
        if (model == null) {
            return Float.NaN;
        }
        if (pose == null || pose.geometry() != model.loaded.main) {
            pose = new PoseMatrices(model.loaded.main);
        }
        int bone = EyeHeight.viewBone(pose);
        if (bone < 0) {
            return Float.NaN;
        }
        PlayerAnimator animator = models.animatorFor(player, model);
        PlayerInputsAdapter.fill(snapshot, player, pt);
        BonePose bones = null;
        float bodyYaw = snapshot.bodyYaw;
        try {
            animator.update(snapshot, player.ticksExisted + pt);
            bones = animator.pose();
            bodyYaw = animator.renderBodyYaw();
        } catch (RuntimeException e) {
            if (!model.animationFailed) {
                model.animationFailed = true;
                Futura3D.LOG.error("Animation failed for model {}", model.key, e);
            }
        }
        pose.compute(bones);
        // As RenderPlayer.doRender places the local player: the feet yOffset under the camera entity's position.
        float feet = -player.yOffset;
        ThirdPersonRenderer
            .placement(player, 0, feet, 0, bodyYaw, model.loaded.widthScale, model.loaded.heightScale, placement);
        float point = EyeHeight.pointY(placement, pose, bone, scratch);
        // EntityRenderer.orientCamera: the camera is (yOffset - 1.62), one more block in a bed, under the position.
        float vanillaEye = EyeHeight.VANILLA_EYE - player.yOffset - (player.isPlayerSleeping() ? 1 : 0);
        float offset = EyeHeight.offset(point, vanillaEye, placement[13]);
        if (offset > 0) {
            double x = player.prevPosX + (player.posX - player.prevPosX) * pt;
            double y = player.prevPosY + (player.posY - player.prevPosY) * pt + vanillaEye;
            double z = player.prevPosZ + (player.posZ - player.prevPosZ) * pt;
            offset = EyeHeight.limitUp(offset, room(player.worldObj, x, y, z, offset + EyeHeight.CLEARANCE));
        }
        return offset;
    }

    /** The distance from a point up to the bottom of the first block with a collision box above it, within a range. */
    private static float room(World world, double x, double y, double z, float range) {
        int bx = MathHelper.floor_double(x);
        int bz = MathHelper.floor_double(z);
        int from = MathHelper.floor_double(y);
        int to = MathHelper.floor_double(y + range);
        for (int by = from; by <= to; by++) {
            Block block = world.getBlock(bx, by, bz);
            AxisAlignedBB box = block.getCollisionBoundingBoxFromPool(world, bx, by, bz);
            if (box != null && box.maxY > y) {
                return (float) Math.max(0, box.minY - y);
            }
        }
        return Float.MAX_VALUE;
    }
}

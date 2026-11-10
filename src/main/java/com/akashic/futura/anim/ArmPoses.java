package com.akashic.futura.anim;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.akashic.futura.geo.BakedGeometry;

/**
 * Arm poses another mod gives a player for a moment ({@code FuturaModels.setArmPose}): what Kunftig's held 3D models
 * (AkashicFishing's fish) do to the vanilla biped's arms, which a model replaces. Each pose is the rotation of the arm
 * from hanging straight down, in radians, as {@code ModelBiped}'s {@code rotateAngleX/Y/Z} (forward raise is negative
 * x); it replaces the animated rotation of the model's {@code RightArm}/{@code LeftArm} for a short while
 * ({@link #VALID_NANOS}), so a mod that stops sending (the item put away, the mod gone) lets the animation back at
 * once. Bounded like {@link ExternalStates}. No Minecraft classes; one thread.
 */
public final class ArmPoses {

    public static final String RIGHT_ARM = "RightArm";
    public static final String LEFT_ARM = "LeftArm";
    /** A pose lasts this long after it was set (a few frames even at low frame rates). */
    public static final long VALID_NANOS = 250_000_000L;
    public static final int MAX_PLAYERS = 512;

    private static final class Pose {

        float[] right;
        float[] left;
        long at;
    }

    private final Map<UUID, Pose> poses = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Sets a player's arm poses at {@code nanos}; null for an arm leaves it to the animation.
     *
     * @return false for a pose that is not three finite numbers (nothing changes)
     */
    public boolean set(UUID player, float[] right, float[] left, long nanos) {
        if (player == null || !valid(right) || !valid(left)) {
            return false;
        }
        if (right == null && left == null) {
            poses.remove(player);
            return true;
        }
        Pose p = poses.get(player);
        if (p == null) {
            if (poses.size() >= MAX_PLAYERS) {
                poses.remove(
                    poses.keySet()
                        .iterator()
                        .next());
            }
            p = new Pose();
            poses.put(player, p);
        }
        p.right = right == null ? null : right.clone();
        p.left = left == null ? null : left.clone();
        p.at = nanos;
        return true;
    }

    private static boolean valid(float[] v) {
        if (v == null) {
            return true;
        }
        if (v.length != 3) {
            return false;
        }
        for (float f : v) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes a player's poses, if fresh at {@code nanos}, over the arm bones of an animated pose.
     *
     * @return whether a pose was applied
     */
    public boolean apply(UUID player, BonePose pose, BakedGeometry geometry, long nanos) {
        Pose p = poses.get(player);
        if (p == null || pose == null) {
            return false;
        }
        if (nanos - p.at > VALID_NANOS) {
            poses.remove(player);
            return false;
        }
        boolean applied = write(pose, geometry.indexOf(RIGHT_ARM), p.right);
        return write(pose, geometry.indexOf(LEFT_ARM), p.left) || applied;
    }

    private static boolean write(BonePose pose, int bone, float[] rotation) {
        if (bone < 0 || rotation == null) {
            return false;
        }
        int o = bone * BonePose.FLOATS;
        // the biped is drawn under scale(-1, -1, 1): its turns about x and y are the opposite of ours, about z the
        // same (as the head's look, PlayerAnimator: headPitch = -pitch, netHeadYaw = -yaw)
        pose.values[o] = -rotation[0];
        pose.values[o + 1] = -rotation[1];
        pose.values[o + 2] = rotation[2];
        return true;
    }

    public void clear() {
        poses.clear();
    }
}

package com.akashic.futura.anim;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.akashic.futura.geo.BakedGeometry;

/**
 * Bone poses another mod gives a player for a moment ({@code FuturaModels.setBonePose}): any bones by name, each with
 * the rotation an animation file would give it (degrees, as Blockbench shows them, relative to the bone's rest
 * rotation), and a weight that blends from the animated rotation (0) to the given one (1). A gesture, a hand sign:
 * what a mod wants of the model without shipping an animation for every model. Bones the model does not have are
 * skipped, so one pose serves models with and without, say, finger bones. Like {@link ArmPoses}, a pose lasts
 * {@link #VALID_NANOS} after it was set and is applied after it, so it also wins over an arm pose. Bounded. No
 * Minecraft classes; one thread.
 */
public final class BonePoses {

    /** A pose lasts this long after it was set (a few frames even at low frame rates). */
    public static final long VALID_NANOS = 250_000_000L;
    public static final int MAX_PLAYERS = 512;
    public static final int MAX_BONES = 48;

    private static final class Pose {

        String[] bones;
        float[] degrees;
        float weight;
        long at;
    }

    private final Map<UUID, Pose> poses = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Sets a player's bone poses at {@code nanos}.
     *
     * @param bones   the bones' names; null or empty clears
     * @param degrees three numbers for each bone: x, y, z as in an animation file
     * @param weight  0 (the animation's rotation) to 1 (the given rotation)
     * @return false for too many bones, a null name, a count that does not match or a number that is not finite
     *         (nothing changes)
     */
    public boolean set(UUID player, String[] bones, float[] degrees, float weight, long nanos) {
        if (player == null) {
            return false;
        }
        if (bones == null || bones.length == 0) {
            poses.remove(player);
            return true;
        }
        if (bones.length > MAX_BONES || degrees == null
            || degrees.length != bones.length * 3
            || Float.isNaN(weight)
            || Float.isInfinite(weight)) {
            return false;
        }
        for (String bone : bones) {
            if (bone == null) {
                return false;
            }
        }
        for (float f : degrees) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                return false;
            }
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
        p.bones = bones.clone();
        p.degrees = degrees.clone();
        p.weight = weight < 0 ? 0 : weight > 1 ? 1 : weight;
        p.at = nanos;
        return true;
    }

    /**
     * Blends a player's poses, if fresh at {@code nanos}, into the bones of an animated pose.
     *
     * @return whether any bone was posed
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
        boolean applied = false;
        for (int i = 0; i < p.bones.length; i++) {
            int bone = geometry.indexOf(p.bones[i]);
            if (bone < 0) {
                continue;
            }
            int o = bone * BonePose.FLOATS;
            // as the clips are compiled (ClipCompiler): degrees to radians, x and y negated, z kept
            blend(pose.values, o, -p.degrees[i * 3] * AnimMath.DEG_TO_RAD, p.weight);
            blend(pose.values, o + 1, -p.degrees[i * 3 + 1] * AnimMath.DEG_TO_RAD, p.weight);
            blend(pose.values, o + 2, p.degrees[i * 3 + 2] * AnimMath.DEG_TO_RAD, p.weight);
            applied = true;
        }
        return applied;
    }

    private static void blend(float[] values, int at, float target, float weight) {
        values[at] += (target - values[at]) * weight;
    }

    public void clear() {
        poses.clear();
    }
}

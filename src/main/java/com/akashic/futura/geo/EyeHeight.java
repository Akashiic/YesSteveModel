/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the view point of src/main/java/com/elfmcys/ysm/capability/PlayerAnimatableCapability.java (codeAnimation: the
 * ViewLocator bone, else Head) and src/main/java/com/elfmcys/ysm/client/compat/FirstPersonCompat.java (the point
 * sits at 24 px, 1.5 blocks, when the camera is vanilla's), Apache License 2.0.
 * Changes: upstream only feeds the First Person Model mod (it moves the body under a fixed camera, from the bones' rest
 * pivots); here the camera itself moves, from the bone's pivot in the current animated pose, smoothed and clamped.
 */
package com.akashic.futura.geo;

/**
 * The first-person camera height from a model. No Minecraft classes.
 *
 * <p>
 * The view point is the pivot of the model's {@code ViewLocator} bone (the first of that locator group), or else of
 * its {@code Head} bone, in the current pose and placement (the model's scale and animated parents included; the
 * bone's own rotation does not move its pivot, so looking around does not move the camera). Upstream puts that point
 * {@link #EYE_ABOVE_POINT} below the eye (24 px under vanilla's 1.62), so the camera goes to
 * {@code point + EYE_ABOVE_POINT}; only the height is used, the camera stays on the player's axis.
 */
public final class EyeHeight {

    public static final String VIEW_LOCATOR = "ViewLocator";
    public static final String HEAD = "Head";
    /** Vanilla's eye height over the feet. */
    public static final float VANILLA_EYE = 1.62f;
    /** Upstream {@code FirstPersonCompat}: the view point of a model at the vanilla camera is 24 px over the feet. */
    public static final float REFERENCE = 24 / 16f;
    /** The camera over the view point. */
    public static final float EYE_ABOVE_POINT = VANILLA_EYE - REFERENCE;
    /** Bounds of the camera's move from vanilla's height, in blocks. */
    public static final float MIN_OFFSET = -1.5f;
    public static final float MAX_OFFSET = 2.5f;
    /** The camera never goes lower than this over the model's origin (the feet standing). */
    public static final float MIN_OVER_FEET = 0.2f;
    /** Room kept between the camera and a block above it (the near plane is 0.05). */
    public static final float CLEARANCE = 0.1f;
    /** Smoothing time constant, seconds. */
    public static final float SMOOTHING = 0.1f;

    private EyeHeight() {}

    /** The bone the camera follows: the first {@code ViewLocator} (group), else the first {@code Head}; -1 if none. */
    public static int viewBone(PoseMatrices pose) {
        int[] bones = pose.group(VIEW_LOCATOR);
        if (bones.length == 0) {
            bones = pose.group(HEAD);
        }
        return bones.length == 0 ? -1 : bones[0];
    }

    /**
     * The height of a bone's pivot after the pose and the placement.
     *
     * @param placement model space to render space ({@link Mat4}, column-major), the model's scale included
     * @param scratch   a {@code float[16]}
     */
    public static float pointY(float[] placement, PoseMatrices pose, int bone, float[] scratch) {
        pose.locator(bone, scratch, 0);
        // row 1 of placement times the locator's origin (column 3), column-major
        return placement[1] * scratch[12] + placement[5] * scratch[13]
            + placement[9] * scratch[14]
            + placement[13] * scratch[15];
    }

    /**
     * How far the camera moves up (negative: down) from vanilla's height.
     *
     * @param pointY     the view point's height ({@link #pointY})
     * @param vanillaEye vanilla's camera height in the same space
     * @param feetY      the model's origin height in the same space
     */
    public static float offset(float pointY, float vanillaEye, float feetY) {
        if (!finite(pointY) || !finite(vanillaEye) || !finite(feetY)) {
            return 0;
        }
        float offset = pointY + EYE_ABOVE_POINT - vanillaEye;
        float lowest = feetY + MIN_OVER_FEET - vanillaEye;
        if (offset < lowest) {
            offset = lowest;
        }
        return clamp(offset, MIN_OFFSET, MAX_OFFSET);
    }

    /**
     * An upward move limited by the room over vanilla's camera (to the bottom of the block above, less
     * {@link #CLEARANCE}); a downward move is kept.
     *
     * @param room the distance from vanilla's camera up to the first solid block, or a large value for none
     */
    public static float limitUp(float offset, float room) {
        if (offset <= 0) {
            return offset;
        }
        return Math.max(0, Math.min(offset, room - CLEARANCE));
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /**
     * Follows a target exponentially ({@link #SMOOTHING} time constant), frame-rate independent: no jitter from a
     * pose that moves a little every frame, no jump when the pose changes. A pause longer than half a second snaps.
     */
    public static final class Smoother {

        private float value;

        public float value() {
            return value;
        }

        /** Back to vanilla's height. */
        public void reset() {
            value = 0;
        }

        /**
         * @param dt seconds since the last update (0 for a second call in the same frame)
         */
        public float update(float target, float dt) {
            if (!finite(target)) {
                return value;
            }
            if (dt > 0.5f) {
                value = target;
            } else if (dt > 0) {
                float k = 1 - (float) Math.exp(-dt / SMOOTHING);
                value += (target - value) * k;
                if (Math.abs(target - value) < 1e-4f) {
                    value = target;
                }
            }
            return value;
        }
    }
}

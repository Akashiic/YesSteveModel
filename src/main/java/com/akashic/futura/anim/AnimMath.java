/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/util/MathUtil.java, Apache License 2.0.
 * Changes: plain float arrays instead of JOML vectors (no allocation per call); the JOML quaternion code that upstream
 * calls (rotateZYX, nlerp) is written out here.
 */
package com.akashic.futura.anim;

/** Interpolation helpers of the animation runtime. Vectors are {@code float[]} with an offset. */
public final class AnimMath {

    public static final float DEG_TO_RAD = (float) (Math.PI / 180);
    public static final float RAD_TO_DEG = (float) (180 / Math.PI);

    private AnimMath() {}

    public static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    /** Upstream's Catmull-Rom (tension 0.5) between {@code begin} and {@code end}. */
    public static float catmullRom(float t, float left, float begin, float end, float right) {
        float v0 = (end - left) * 0.5f;
        float v1 = (right - begin) * 0.5f;
        float t2 = t * t;
        float t3 = t * t2;
        return (2 * begin - 2 * end + v0 + v1) * t3 + (-3 * begin + 3 * end - 2 * v0 - v1) * t2 + v0 * t + begin;
    }

    /** {@code 1 + (value - 1) * weight}: a scale weighted towards 1. */
    public static float weightedScale(float value, float weight) {
        return 1f + (value - 1f) * weight;
    }

    /**
     * Interpolates two rotations (radians, relative to the bone's rest rotation {@code init}) through normalised
     * quaternion lerp of the absolute rotations, as upstream {@code MathUtil.lerpRotationValues}.
     *
     * @param scratch at least 8 floats
     */
    public static void lerpRotation(float t, float[] begin, int bo, float[] end, int eo, float[] init, int io,
        float[] dst, int dOff, float[] scratch) {
        quatZYX(begin[bo] + init[io], begin[bo + 1] + init[io + 1], begin[bo + 2] + init[io + 2], scratch, 0);
        quatZYX(end[eo] + init[io], end[eo + 1] + init[io + 1], end[eo + 2] + init[io + 2], scratch, 4);
        float x1 = scratch[0], y1 = scratch[1], z1 = scratch[2], w1 = scratch[3];
        float x2 = scratch[4], y2 = scratch[5], z2 = scratch[6], w2 = scratch[7];
        // JOML Quaternionf.nlerp
        float cosom = x1 * x2 + y1 * y2 + z1 * z2 + w1 * w2;
        float s0 = 1f - t;
        float s1 = cosom >= 0f ? t : -t;
        float x = s0 * x1 + s1 * x2;
        float y = s0 * y1 + s1 * y2;
        float z = s0 * z1 + s1 * z2;
        float w = s0 * w1 + s1 * w2;
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (len == 0) {
            dst[dOff] = 0;
            dst[dOff + 1] = 0;
            dst[dOff + 2] = 0;
            return;
        }
        float inv = 1f / len;
        x *= inv;
        y *= inv;
        z *= inv;
        w *= inv;
        // MathUtil.getEulerAnglesZYX (upstream's fixed version)
        float ex = (float) Math.atan2(y * z + w * x, 0.5f - x * x - y * y);
        float ey = safeAsin(-2.0f * (x * z - w * y));
        float ez = (float) Math.atan2(x * y + w * z, 0.5f - y * y - z * z);
        dst[dOff] = ex - init[io];
        dst[dOff + 1] = ey - init[io + 1];
        dst[dOff + 2] = ez - init[io + 2];
    }

    /** JOML {@code new Quaternionf().rotateZYX(z, y, x)}: the quaternion of Rz·Ry·Rx, as (x, y, z, w). */
    static void quatZYX(float angleX, float angleY, float angleZ, float[] out, int o) {
        float sx = (float) Math.sin(angleX * 0.5f), cx = (float) Math.cos(angleX * 0.5f);
        float sy = (float) Math.sin(angleY * 0.5f), cy = (float) Math.cos(angleY * 0.5f);
        float sz = (float) Math.sin(angleZ * 0.5f), cz = (float) Math.cos(angleZ * 0.5f);
        float cycz = cy * cz;
        float sysz = sy * sz;
        float sycz = sy * cz;
        float cysz = cy * sz;
        out[o] = sx * cycz - cx * sysz;
        out[o + 1] = cx * sycz + sx * cysz;
        out[o + 2] = cx * cysz - sx * sycz;
        out[o + 3] = cx * cycz + sx * sysz;
    }

    private static float safeAsin(float v) {
        return v <= -1f ? (float) (-Math.PI / 2) : v >= 1f ? (float) (Math.PI / 2) : (float) Math.asin(v);
    }

    /** Minecraft's {@code Mth.wrapDegrees}: into [-180, 180). */
    public static float wrapDegrees(float degrees) {
        float f = degrees % 360f;
        if (f >= 180f) {
            f -= 360f;
        }
        if (f < -180f) {
            f += 360f;
        }
        return f;
    }

    public static float clamp(float v, float min, float max) {
        return v < min ? min : v > max ? max : v;
    }

    /** NaN and infinities become 0: the pose never carries them. */
    public static float finite(float v) {
        return Float.isNaN(v) || Float.isInfinite(v) ? 0 : v;
    }
}

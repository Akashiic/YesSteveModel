package com.akashic.futura.geo;

/**
 * 4x4 affine matrices as {@code float[16]} in OpenGL (column-major) order, read and written at an offset so that many
 * matrices can share one array. Every operation multiplies on the right, as {@code glTranslate}/{@code glRotate} do.
 * No Minecraft classes.
 */
public final class Mat4 {

    public static final int FLOATS = 16;

    private Mat4() {}

    public static void identity(float[] m, int o) {
        for (int i = 0; i < 16; i++) {
            m[o + i] = 0;
        }
        m[o] = 1;
        m[o + 5] = 1;
        m[o + 10] = 1;
        m[o + 15] = 1;
    }

    public static float[] identity() {
        float[] m = new float[16];
        identity(m, 0);
        return m;
    }

    public static void copy(float[] src, int so, float[] dst, int d) {
        System.arraycopy(src, so, dst, d, 16);
    }

    /** {@code out = a · b}; {@code out} may alias neither input. */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        for (int c = 0; c < 4; c++) {
            float b0 = b[bo + c * 4], b1 = b[bo + c * 4 + 1], b2 = b[bo + c * 4 + 2], b3 = b[bo + c * 4 + 3];
            for (int r = 0; r < 4; r++) {
                out[oo + c * 4 + r] = a[ao + r] * b0 + a[ao + 4 + r] * b1 + a[ao + 8 + r] * b2 + a[ao + 12 + r] * b3;
            }
        }
    }

    /** {@code m = m · T(x, y, z)}. */
    public static void translate(float[] m, int o, float x, float y, float z) {
        for (int r = 0; r < 4; r++) {
            m[o + 12 + r] += m[o + r] * x + m[o + 4 + r] * y + m[o + 8 + r] * z;
        }
    }

    /** {@code m = m · S(x, y, z)}. */
    public static void scale(float[] m, int o, float x, float y, float z) {
        for (int r = 0; r < 4; r++) {
            m[o + r] *= x;
            m[o + 4 + r] *= y;
            m[o + 8 + r] *= z;
        }
    }

    /** {@code m = m · Rx(radians)}. */
    public static void rotateX(float[] m, int o, float radians) {
        if (radians == 0) {
            return;
        }
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        for (int r = 0; r < 4; r++) {
            float y = m[o + 4 + r], z = m[o + 8 + r];
            m[o + 4 + r] = y * c + z * s;
            m[o + 8 + r] = z * c - y * s;
        }
    }

    /** {@code m = m · Ry(radians)}. */
    public static void rotateY(float[] m, int o, float radians) {
        if (radians == 0) {
            return;
        }
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        for (int r = 0; r < 4; r++) {
            float x = m[o + r], z = m[o + 8 + r];
            m[o + r] = x * c - z * s;
            m[o + 8 + r] = x * s + z * c;
        }
    }

    /** {@code m = m · Rz(radians)}. */
    public static void rotateZ(float[] m, int o, float radians) {
        if (radians == 0) {
            return;
        }
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        for (int r = 0; r < 4; r++) {
            float x = m[o + r], y = m[o + 4 + r];
            m[o + r] = x * c + y * s;
            m[o + 4 + r] = y * c - x * s;
        }
    }

    /** Transforms the point (x, y, z, 1); writes x, y, z to {@code out}. */
    public static void transformPoint(float[] m, int o, float x, float y, float z, float[] out) {
        for (int r = 0; r < 3; r++) {
            out[r] = m[o + r] * x + m[o + 4 + r] * y + m[o + 8 + r] * z + m[o + 12 + r];
        }
    }
}

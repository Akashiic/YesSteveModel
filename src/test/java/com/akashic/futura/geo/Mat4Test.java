package com.akashic.futura.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Mat4Test {

    static final float EPS = 1e-5f;
    static final float HALF_PI = (float) (Math.PI / 2);

    static float[] point(float[] m, float x, float y, float z) {
        float[] out = new float[3];
        Mat4.transformPoint(m, 0, x, y, z, out);
        return out;
    }

    /** The inverse of an affine matrix (rotation, uniform or non-uniform scale and translation). */
    static float[] inverse(float[] m) {
        double[] a = new double[16];
        for (int i = 0; i < 16; i++) {
            a[i] = m[i];
        }
        // 3x3 inverse of the linear part (column-major: a[c * 4 + r]).
        double m00 = a[0], m10 = a[1], m20 = a[2], m01 = a[4], m11 = a[5], m21 = a[6], m02 = a[8], m12 = a[9],
            m22 = a[10];
        double det = m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20);
        double[][] inv = {
            { (m11 * m22 - m12 * m21) / det, (m02 * m21 - m01 * m22) / det, (m01 * m12 - m02 * m11) / det },
            { (m12 * m20 - m10 * m22) / det, (m00 * m22 - m02 * m20) / det, (m02 * m10 - m00 * m12) / det },
            { (m10 * m21 - m11 * m20) / det, (m01 * m20 - m00 * m21) / det, (m00 * m11 - m01 * m10) / det } };
        float[] out = Mat4.identity();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[c * 4 + r] = (float) inv[r][c];
            }
        }
        for (int r = 0; r < 3; r++) {
            out[12 + r] = (float) -(inv[r][0] * a[12] + inv[r][1] * a[13] + inv[r][2] * a[14]);
        }
        return out;
    }

    static float[] mul(float[] a, float[] b) {
        float[] out = new float[16];
        Mat4.mul(a, 0, b, 0, out, 0);
        return out;
    }

    @Test
    void rotationsFollowTheRightHandRuleLikeGlRotate() {
        float[] m = Mat4.identity();
        Mat4.rotateX(m, 0, HALF_PI);
        assertArrayEquals(new float[] { 0, 0, 1 }, point(m, 0, 1, 0), EPS);
        m = Mat4.identity();
        Mat4.rotateY(m, 0, HALF_PI);
        assertArrayEquals(new float[] { 0, 0, -1 }, point(m, 1, 0, 0), EPS);
        m = Mat4.identity();
        Mat4.rotateZ(m, 0, HALF_PI);
        assertArrayEquals(new float[] { 0, 1, 0 }, point(m, 1, 0, 0), EPS);
    }

    @Test
    void operationsMultiplyOnTheRight() {
        // T(1, 0, 0) · Rz(90°) · S(2): (1, 0, 0) -> (2, 0, 0) -> (0, 2, 0) -> (1, 2, 0).
        float[] m = Mat4.identity();
        Mat4.translate(m, 0, 1, 0, 0);
        Mat4.rotateZ(m, 0, HALF_PI);
        Mat4.scale(m, 0, 2, 2, 2);
        assertArrayEquals(new float[] { 1, 2, 0 }, point(m, 1, 0, 0), EPS);
        float[] t = Mat4.identity();
        Mat4.translate(t, 0, 1, 0, 0);
        float[] r = Mat4.identity();
        Mat4.rotateZ(r, 0, HALF_PI);
        Mat4.scale(r, 0, 2, 2, 2);
        assertArrayEquals(m, mul(t, r), EPS);
    }

    @Test
    void inverseHelperInverts() {
        float[] m = Mat4.identity();
        Mat4.translate(m, 0, 0.3f, -2, 5);
        Mat4.rotateY(m, 0, 0.7f);
        Mat4.rotateX(m, 0, -1.1f);
        Mat4.scale(m, 0, 0.5f, 2, 1.5f);
        assertArrayEquals(Mat4.identity(), mul(m, inverse(m)), 1e-4f);
        assertEquals(1, mul(inverse(m), m)[15], EPS);
    }
}

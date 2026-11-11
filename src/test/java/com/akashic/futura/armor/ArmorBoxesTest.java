package com.akashic.futura.armor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ArmorBoxesTest {

    private static final int Q = ArmorBoxes.QUAD_FLOATS;

    @Test
    void aWholeBoxIsModelBoxs() {
        float[] q = ArmorBoxes.quads(ArmorFit.Part.HEAD, 1, -8, 0);
        assertEquals(6 * Q, q.length);
        // the front face (fifth): texture (8, 8) to (16, 16) on 64x32, corners one pixel out of the 8x8x8 box
        int o = 4 * Q;
        float minU = 1, maxU = 0, minV = 1, maxV = 0;
        for (int k = 0; k < 4; k++) {
            assertEquals(-5, q[o + k * 5 + 2], 1e-6f);
            assertEquals(5, Math.abs(q[o + k * 5]), 1e-6f);
            minU = Math.min(minU, q[o + k * 5 + 3]);
            maxU = Math.max(maxU, q[o + k * 5 + 3]);
            minV = Math.min(minV, q[o + k * 5 + 4]);
            maxV = Math.max(maxV, q[o + k * 5 + 4]);
        }
        assertEquals(8 / 64f, minU, 1e-6f);
        assertEquals(16 / 64f, maxU, 1e-6f);
        assertEquals(8 / 32f, minV, 1e-6f);
        assertEquals(16 / 32f, maxV, 1e-6f);
    }

    @Test
    void anArmIsCutAtTheElbow() {
        float[] upper = ArmorBoxes.quads(ArmorFit.Part.RIGHT_ARM, 1, -2, 4);
        float[] lower = ArmorBoxes.quads(ArmorFit.Part.RIGHT_ARM, 1, 4, 10);
        // no bottom on the upper cut, no top on the lower one
        assertEquals(5 * Q, upper.length);
        assertEquals(5 * Q, lower.length);
        // the inflated arm runs from -3 to 11: the cut is halfway, at 4
        float[] uy = range(upper, 1);
        float[] ly = range(lower, 1);
        assertEquals(-3, uy[0], 1e-6f);
        assertEquals(4, uy[1], 1e-6f);
        assertEquals(4, ly[0], 1e-6f);
        assertEquals(11, ly[1], 1e-6f);
        // the sides take rows 20 to 26 and 26 to 32 of the texture
        float[] uv = range(sides(upper), 4);
        float[] lv = range(sides(lower), 4);
        assertEquals(20 / 32f, uv[0], 1e-6f);
        assertEquals(26 / 32f, uv[1], 1e-6f);
        assertEquals(26 / 32f, lv[0], 1e-6f);
        assertEquals(32 / 32f, lv[1], 1e-6f);
    }

    /** Mirrored or not, cut or whole, every face looks out of the box, as ModelBox's do after {@code flipFace}. */
    @Test
    void facesLookOut() {
        for (ArmorFit.Part part : ArmorFit.Part.values()) {
            float mid = part.y + part.height / 2f;
            float[][] cuts = { ArmorBoxes.quads(part, 0.5f, part.y, part.y + part.height),
                ArmorBoxes.quads(part, 1, part.y, mid), ArmorBoxes.quads(part, 1, mid, part.y + part.height) };
            float cx = part.x + part.width / 2f;
            float cz = part.z + part.depth / 2f;
            for (float[] q : cuts) {
                float cy = (range(q, 1)[0] + range(q, 1)[1]) / 2;
                for (int o = 0; o < q.length; o += Q) {
                    float fx = 0, fy = 0, fz = 0;
                    for (int k = 0; k < 4; k++) {
                        fx += q[o + k * 5] / 4;
                        fy += q[o + k * 5 + 1] / 4;
                        fz += q[o + k * 5 + 2] / 4;
                    }
                    float dot = (fx - cx) * q[o + 20] + (fy - cy) * q[o + 21] + (fz - cz) * q[o + 22];
                    assertTrue(dot > 0, part + " quad " + o / Q);
                }
            }
        }
    }

    @Test
    void theHelmetsOuterLayerIsHalfAPixelLarger() {
        ArmorFit.Piece headwear = ArmorFit.of("builtin:misc/2_steve")
            .whole(ArmorFit.Part.HEADWEAR);
        float[] x = range(ArmorBoxes.quads(headwear, 1), 0);
        assertEquals(-5.5f, x[0], 1e-6f);
        assertEquals(5.5f, x[1], 1e-6f);
    }

    /** The side faces only (the first two and the last two). */
    private static float[] sides(float[] q) {
        int n = q.length / Q;
        float[] out = new float[4 * Q];
        System.arraycopy(q, 0, out, 0, 2 * Q);
        System.arraycopy(q, (n - 2) * Q, out, 2 * Q, 2 * Q);
        return out;
    }

    private static float[] range(float[] q, int field) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int o = 0; o < q.length; o += Q) {
            for (int k = 0; k < 4; k++) {
                lo = Math.min(lo, q[o + k * 5 + field]);
                hi = Math.max(hi, q[o + k * 5 + field]);
            }
        }
        return new float[] { lo, hi };
    }
}

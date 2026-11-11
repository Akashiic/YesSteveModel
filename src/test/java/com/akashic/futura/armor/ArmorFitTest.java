package com.akashic.futura.armor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.geo.Mat4;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

class ArmorFitTest {

    private static final String[] FITTED = { "default", "misc/2_steve", "misc/1_alex" };

    @Test
    void onlyTheDefaultAndSteveAlexWearArmor() {
        for (String m : FITTED) {
            assertNotNull(ArmorFit.of("builtin:" + m), m);
        }
        assertNull(ArmorFit.of("builtin:wine_fox/14_momo"));
        assertNull(ArmorFit.of("builtin:misc/3_default_boy"));
        assertNull(ArmorFit.of(null));
    }

    @Test
    void steveHeadIsTheBipedHead() {
        ArmorFit.Piece head = ArmorFit.of("builtin:misc/2_steve").pieces.get(0);
        float[] m = new float[16];
        ArmorFit.matrix(head, new float[] { 0, 24, 0 }, m);
        float[] out = new float[3];
        // the biped head's top right front corner: half a block up, a quarter to the right (+x) and to the front (-z)
        Mat4.transformPoint(m, 0, -4, -8, -4, out);
        assertEquals(0.25f, out[0], 1e-6f);
        assertEquals(0.5f, out[1], 1e-6f);
        assertEquals(-0.25f, out[2], 1e-6f);
    }

    @Test
    void everyCutCoversItsBoneBox() {
        for (String m : FITTED) {
            ArmorFit fit = ArmorFit.of("builtin:" + m);
            float[] pivot = { 1.5f, 20, -0.5f };
            float[] matrix = new float[16];
            float[] out = new float[3];
            for (ArmorFit.Piece p : fit.pieces) {
                ArmorFit.matrix(p, pivot, matrix);
                float[] lo = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
                float[] hi = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
                for (int c = 0; c < 8; c++) {
                    float x = (c & 1) == 0 ? p.part.x : p.part.x + p.part.width;
                    float y = (c & 2) == 0 ? p.from : p.to;
                    float z = (c & 4) == 0 ? p.part.z : p.part.z + p.part.depth;
                    Mat4.transformPoint(matrix, 0, x, y, z, out);
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], out[i]);
                        hi[i] = Math.max(hi[i], out[i]);
                    }
                }
                // the bone's box in its locator frame: blocks from the pivot, x negated
                float[] b = p.box;
                String what = m + " " + p.part + " on " + p.bone;
                assertEquals(-(b[0] + b[3] - pivot[0]) / 16, lo[0], 1e-5f, what);
                assertEquals(-(b[0] - pivot[0]) / 16, hi[0], 1e-5f, what);
                assertEquals((b[1] - pivot[1]) / 16, lo[1], 1e-5f, what);
                assertEquals((b[1] + b[4] - pivot[1]) / 16, hi[1], 1e-5f, what);
                assertEquals((b[2] - pivot[2]) / 16, lo[2], 1e-5f, what);
                assertEquals((b[2] + b[5] - pivot[2]) / 16, hi[2], 1e-5f, what);
            }
        }
    }

    @Test
    void cutsMeetAndCoverEveryPart() {
        for (String m : FITTED) {
            ArmorFit fit = ArmorFit.of("builtin:" + m);
            Set<ArmorFit.Part> seen = EnumSet.noneOf(ArmorFit.Part.class);
            for (ArmorFit.Part part : ArmorFit.Part.values()) {
                float at = part.y;
                for (ArmorFit.Piece p : fit.pieces) {
                    if (p.part == part) {
                        assertEquals(at, p.from, 1e-6f, m + " " + part);
                        at = p.to;
                        seen.add(part);
                    }
                }
                assertEquals(part.y + part.height, at, 1e-6f, m + " " + part);
                ArmorFit.Piece whole = fit.whole(part);
                assertTrue(whole.top() && whole.bottom(), m + " " + part);
            }
            assertEquals(EnumSet.allOf(ArmorFit.Part.class), seen, m);
        }
    }

    /** Each cut sits on a bone the model has, over most of that bone's own cubes. */
    @Test
    void theBonesAreTheModels() throws IOException {
        for (String m : FITTED) {
            LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + m + "/"));
            BakedGeometry g = model.main;
            for (ArmorFit.Piece p : ArmorFit.of("builtin:" + m).pieces) {
                String what = m + " " + p.part + " on " + p.bone;
                int bone = g.indexOf(p.bone);
                assertTrue(bone >= 0, what);
                float[] q = g.bones[bone].quads;
                assertTrue(q.length > 0, what);
                float[] lo = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
                float[] hi = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
                for (int o = 0; o < q.length; o += BakedGeometry.QUAD_FLOATS) {
                    for (int k = 0; k < 4; k++) {
                        for (int i = 0; i < 3; i++) {
                            float v = q[o + k * 5 + i] * 16 * (i == 0 ? -1 : 1);
                            lo[i] = Math.min(lo[i], v);
                            hi[i] = Math.max(hi[i], v);
                        }
                    }
                }
                double inside = 1;
                double all = 1;
                for (int i = 0; i < 3; i++) {
                    float a = Math.max(lo[i], p.box[i]);
                    float b = Math.min(hi[i], p.box[i] + p.box[i + 3]);
                    inside *= Math.max(0, b - a);
                    all *= hi[i] - lo[i];
                }
                assertTrue(inside / all > 0.6, what + ": " + inside / all);
            }
        }
    }
}

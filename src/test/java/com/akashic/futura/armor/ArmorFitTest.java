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
    void theDefaultAndSteveAlexAreTunedByHand() {
        for (String m : FITTED) {
            assertNotNull(ArmorFit.of("builtin:" + m), m);
        }
        assertNull(ArmorFit.of("builtin:wine_fox/14_momo"));
        assertNull(ArmorFit.of(null));
    }

    /** Every built-in model wears the whole armor: the other models get their fit from their bones. */
    @Test
    void everyModelGetsAFitFromItsBones() throws IOException {
        java.util.List<String> folders = new java.util.ArrayList<>();
        for (String line : new String(
            java.nio.file.Files
                .readAllBytes(java.nio.file.Paths.get("src/main/resources/assets/futura3d/builtin/index.txt")),
            java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                folders.add(line);
            }
        }
        assertTrue(folders.size() > 20);
        for (String m : folders) {
            LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + m + "/"));
            ArmorFit fit = ArmorFit.of("builtin:" + m, model.main);
            assertNotNull(fit, m);
            for (ArmorFit.Part part : ArmorFit.Part.values()) {
                ArmorFit.Piece whole = fit.whole(part);
                assertNotNull(whole, m + " " + part);
                for (int i = 3; i < 6; i++) {
                    assertTrue(whole.box[i] > 0.4f && whole.box[i] < 30, m + " " + part + " " + whole.box[i]);
                }
            }
            // the same fit again for the same geometry
            assertTrue(fit == ArmorFit.of("builtin:" + m, model.main), m);
        }
    }

    /** On Steve, the fit found from the bones is the hand-tuned one (the bones are the biped's boxes). */
    @Test
    void steveFromItsBonesIsTheTunedFit() throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/misc/2_steve/"));
        ArmorFit found = ArmorFit.fromGeometry(model.main);
        ArmorFit tuned = ArmorFit.of("builtin:misc/2_steve");
        // the head: the tuned box, grown over the hat layer (half a pixel), never down
        ArmorFit.Piece a = found.whole(ArmorFit.Part.HEAD);
        ArmorFit.Piece b = tuned.whole(ArmorFit.Part.HEAD);
        assertEquals(b.bone, a.bone);
        assertEquals(b.box[1], a.box[1], 0.01f);
        for (int i = 0; i < 3; i++) {
            assertTrue(a.box[i] <= b.box[i] + 0.01f && a.box[i] + a.box[i + 3] >= b.box[i] + b.box[i + 3] - 0.01f);
            assertTrue(a.box[i + 3] <= b.box[i + 3] + 2 * ArmorFit.HAIR_TOP, "head " + i);
        }
        // the arms cut at the elbow, the body in two (UpBody under UpperBody), on the same bones
        for (ArmorFit.Part part : new ArmorFit.Part[] { ArmorFit.Part.BODY, ArmorFit.Part.RIGHT_ARM,
            ArmorFit.Part.LEFT_ARM }) {
            java.util.List<String> foundBones = new java.util.ArrayList<>();
            java.util.List<String> tunedBones = new java.util.ArrayList<>();
            for (ArmorFit.Piece p : found.pieces) {
                if (p.part == part) {
                    foundBones.add(p.bone);
                }
            }
            for (ArmorFit.Piece p : tuned.pieces) {
                if (p.part == part) {
                    tunedBones.add(p.bone);
                }
            }
            assertEquals(tunedBones, foundBones, part.name());
        }
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

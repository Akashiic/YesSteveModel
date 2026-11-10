package com.akashic.futura.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ModelSource;

class FacePartitionTest {

    /** A 16x16 alpha map: opaque everywhere except the region given. */
    private static FacePartition.Alpha alpha(int x0, int y0, int x1, int y1, int value) {
        byte[] a = new byte[16 * 16];
        Arrays.fill(a, (byte) 255);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                a[y * 16 + x] = (byte) value;
            }
        }
        return new FacePartition.Alpha(16, 16, a);
    }

    private static float t(int texel) {
        return texel / 16f;
    }

    /** A quad's UVs, vertex order as GeoBaker writes them unmirrored: (u1,v0) (u0,v0) (u0,v1) (u1,v1). */
    private static float[] rect(float u0, float v0, float u1, float v1) {
        return new float[] { u1, v0, u0, v0, u0, v1, u1, v1 };
    }

    private static int type(FacePartition.Alpha a, float u0, float v0, float u1, float v1) {
        return FacePartition.quadType(a, rect(u0, v0, u1, v1), 0);
    }

    @Test
    void alphaUnderTheRectangleDecidesTheType() {
        FacePartition.Alpha a = alpha(8, 8, 12, 12, 0);
        assertEquals(FacePartition.TYPE_SOLID, type(a, t(0), t(0), t(8), t(8)));
        assertEquals(FacePartition.TYPE_EMPTY, type(a, t(8), t(8), t(12), t(12)));
        // Alpha 0 and 255 only: cutout (upstream draws it in a translucent partition).
        assertEquals(FacePartition.TYPE_CUTOUT, type(a, t(6), t(6), t(10), t(10)));
        // Corner order does not matter (mirrored or flipped UVs).
        assertEquals(FacePartition.TYPE_EMPTY, type(a, t(12), t(12), t(8), t(8)));
        // A rectangle touching the hole only on its edge does not sample it.
        assertEquals(FacePartition.TYPE_SOLID, type(a, t(4), t(4), t(8), t(8)));
        // Outside the texture is clamped.
        assertEquals(FacePartition.TYPE_SOLID, type(a, -1, -1, t(2), t(2)));
        FacePartition.Alpha half = alpha(0, 0, 4, 4, 128);
        assertEquals(FacePartition.TYPE_TRANSLUCENT, type(half, t(0), t(0), t(4), t(4)));
        // Partial alpha wins over holes.
        FacePartition.Alpha mixed = alpha(0, 0, 2, 4, 128);
        Arrays.fill(mixed.values, 2 * 16, 2 * 16 + 4, (byte) 0);
        assertEquals(FacePartition.TYPE_TRANSLUCENT, type(mixed, t(0), t(0), t(4), t(4)));
    }

    @Test
    void uvsSnapToTexelsAsUpstreamDoes() {
        // u rounds up from 0.6 of a texel, v from 0.4.
        assertEquals(2, FacePartition.quantize(2.55f / 16, 16, 0.6f));
        assertEquals(3, FacePartition.quantize(2.65f / 16, 16, 0.6f));
        assertEquals(3, FacePartition.quantize(2.45f / 16, 16, 0.4f));
        assertEquals(2, FacePartition.quantize(2.35f / 16, 16, 0.4f));
        assertEquals(16, FacePartition.quantize(2f, 16, 0.6f));
        assertEquals(0, FacePartition.quantize(-1f, 16, 0.6f));

        FacePartition.Alpha a = alpha(3, 0, 4, 16, 0);
        float[] uv = rect(2.55f / 16, 1.45f / 16, 4.62f / 16, 3.3f / 16);
        // Snapped to u 2..5, v 2..3: covers the transparent column 3.
        assertEquals(FacePartition.TYPE_CUTOUT, FacePartition.quadType(a, uv, 0));
        assertArrayEquals(rect(t(2), t(2), t(5), t(3)), uv, 1e-7f);
        // Integer UVs are unchanged.
        float[] exact = rect(t(4), t(5), t(9), t(7));
        float[] copy = exact.clone();
        FacePartition.quadType(a, exact, 0);
        assertArrayEquals(copy, exact, 1e-7f);
    }

    @Test
    void degenerateRectanglesAreWidenedByOneTexel() {
        // Column 9 transparent, row 5 transparent.
        FacePartition.Alpha a = alpha(9, 0, 10, 16, 0);
        Arrays.fill(a.values, 5 * 16, 6 * 16, (byte) 0);
        // Zero width at u = 9: the column to its right (9) is sampled; UVs unchanged.
        float[] uv = rect(t(9), t(0), t(9), t(4));
        assertEquals(FacePartition.TYPE_EMPTY, FacePartition.quadType(a, uv, 0));
        assertArrayEquals(rect(t(9), t(0), t(9), t(4)), uv, 1e-7f);
        // Zero width at the right edge: the last column, and vertices 0 and 3 move left by a texel.
        uv = rect(1, t(0), 1, t(4));
        assertEquals(FacePartition.TYPE_SOLID, FacePartition.quadType(a, uv, 0));
        assertArrayEquals(new float[] { t(15), 0, 1, 0, 1, t(4), t(15), t(4) }, uv, 1e-7f);
        // Zero height at v = 6: the row above (5) is sampled, and vertices 2 and 3 move up a texel.
        uv = rect(t(0), t(6), t(4), t(6));
        assertEquals(FacePartition.TYPE_EMPTY, FacePartition.quadType(a, uv, 0));
        assertArrayEquals(new float[] { t(4), t(6), 0, t(6), 0, t(5), t(4), t(5) }, uv, 1e-7f);
        // Zero height at v = 0: row 0, unchanged.
        uv = rect(t(0), 0, t(4), 0);
        assertEquals(FacePartition.TYPE_SOLID, FacePartition.quadType(a, uv, 0));
        assertArrayEquals(rect(t(0), 0, t(4), 0), uv, 1e-7f);
    }

    private static BakedGeometry cube(String cube) throws IOException {
        return GeoBaker.bake(
            FormatGson.create()
                .fromJson(
                    "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"texture_width\":16,"
                        + "\"texture_height\":16},\"bones\":[{\"name\":\"b\",\"pivot\":[0,0,0],\"cubes\":["
                        + cube
                        + "]}]}]}",
                    GeoModel.class));
    }

    @Test
    void cubeShapesFollowTheSixFacesAndTheSign() throws IOException {
        assertEquals(
            BakedGeometry.SHAPE_POSITIVE,
            cube("{\"origin\":[0,0,0],\"size\":[2,2,2],\"uv\":[0,0]}").bones[0].cubeShapes[0]);
        assertEquals(
            BakedGeometry.SHAPE_NEGATIVE,
            cube("{\"origin\":[0,0,0],\"size\":[-2,-2,-2],\"uv\":[0,0]}").bones[0].cubeShapes[0]);
        assertEquals(
            BakedGeometry.SHAPE_OPEN,
            cube("{\"origin\":[0,0,0],\"size\":[2,0,2],\"uv\":[0,0]}").bones[0].cubeShapes[0]);
        assertEquals(
            BakedGeometry.SHAPE_OPEN,
            cube("{\"origin\":[0,0,0],\"size\":[2,-2,2],\"uv\":[0,0]}").bones[0].cubeShapes[0]);
        // Per-face UV with a face missing.
        BakedGeometry open = cube(
            "{\"origin\":[0,0,0],\"size\":[2,2,2],\"uv\":{\"north\":{\"uv\":[0,0],\"uv_size\":[2,2]},"
                + "\"south\":{\"uv\":[0,0],\"uv_size\":[2,2]}}}");
        assertEquals(BakedGeometry.SHAPE_OPEN, open.bones[0].cubeShapes[0]);
        assertEquals(2, open.bones[0].quadCount());
    }

    @Test
    void negativeCubesHaveMirroredWinding() throws IOException {
        // The normal given by each quad's winding points out of a positive cube and into a negative one, so that
        // back-face culling keeps the far faces of a negative cube (the outline shell).
        for (int sign : new int[] { 1, -1 }) {
            BakedGeometry g = cube(
                "{\"origin\":[0,0,0],\"size\":[" + 2 * sign + "," + 2 * sign + "," + 2 * sign + "],\"uv\":[0,0]}");
            float[] q = g.bones[0].quads;
            float[] center = new float[3];
            for (int o = 0; o < q.length; o += BakedGeometry.QUAD_FLOATS) {
                for (int v = 0; v < 4; v++) {
                    for (int k = 0; k < 3; k++) {
                        center[k] += q[o + v * 5 + k] / 24;
                    }
                }
            }
            for (int o = 0; o < q.length; o += BakedGeometry.QUAD_FLOATS) {
                float[] e1 = { q[o + 5] - q[o], q[o + 6] - q[o + 1], q[o + 7] - q[o + 2] };
                float[] e2 = { q[o + 10] - q[o], q[o + 11] - q[o + 1], q[o + 12] - q[o + 2] };
                float[] n = { e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2],
                    e1[0] * e2[1] - e1[1] * e2[0] };
                float dot = 0;
                for (int k = 0; k < 3; k++) {
                    dot += n[k] * (q[o + k] + q[o + 10 + k] - 2 * center[k]);
                }
                assertTrue(sign > 0 ? dot > 0 : dot < 0, "winding of size sign " + sign + ": " + dot);
            }
        }
    }

    private static BakedGeometry bone(String bone) throws IOException {
        return GeoBaker.bake(
            FormatGson.create()
                .fromJson(
                    "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"texture_width\":16,"
                        + "\"texture_height\":16},\"bones\":["
                        + bone
                        + "]}]}",
                    GeoModel.class));
    }

    private static boolean inverted(BakedGeometry g) {
        BakedGeometry.Bone bone = g.bones[0];
        int[] faces = new int[bone.cubeShapes.length];
        for (int c : bone.cubeOfQuad) {
            faces[c]++;
        }
        return FacePartition.inverted(bone, faces)[0];
    }

    @Test
    void invertedCubesAreTheOnesWithAnInwardNormal() throws IOException {
        String uv = ",\"uv\":[0,0]}";
        assertFalse(inverted(cube("{\"origin\":[0,0,0],\"size\":[2,2,2]" + uv)));
        // Negative on three axes (the outline shell), or on one: inverted.
        assertTrue(inverted(cube("{\"origin\":[0,0,0],\"size\":[-2,-2,-2]" + uv)));
        assertTrue(inverted(cube("{\"origin\":[0,0,0],\"size\":[2,-2,2]" + uv)));
        // Negative on two axes: the two mirrors cancel out in the winding, but the normals of those faces still point
        // inward.
        assertTrue(inverted(cube("{\"origin\":[0,0,0],\"size\":[-2,-2,2]" + uv)));
        // Zero thickness: the two faces of that axis are skipped, the rest point outward.
        assertFalse(inverted(cube("{\"origin\":[0,0,0],\"size\":[2,0,2]" + uv)));
        // A cube mirror flips both the layout and the normal; a bone mirror only the layout.
        assertFalse(inverted(cube("{\"origin\":[0,0,0],\"size\":[2,2,2],\"mirror\":true" + uv)));
        assertTrue(
            inverted(
                bone(
                    "{\"name\":\"b\",\"pivot\":[0,0,0],\"mirror\":true,\"cubes\":[{\"origin\":[0,0,0],"
                        + "\"size\":[2,2,2]"
                        + uv
                        + "]}")));
        // Rotation does not change it.
        assertFalse(inverted(cube("{\"origin\":[0,0,0],\"size\":[2,2,2],\"rotation\":[30,45,10]" + uv)));
    }

    @Test
    void partitionsFollowUpstreamRules() throws IOException {
        // Box UV at (0, 0) for a 2x2x2 cube covers u 0..8, v 0..4.
        String box = "{\"origin\":[0,0,0],\"size\":[2,2,2],\"uv\":[0,0]}";
        BakedGeometry positive = cube(box);
        FacePartition.Alpha opaque = alpha(0, 0, 0, 0, 0);
        assertPartitions(FacePartition.classify(positive, opaque, false)[0], FacePartition.OPAQUE_CULL);
        assertPartitions(FacePartition.classify(positive, null, false)[0], FacePartition.OPAQUE_CULL);

        // One transparent texel in the north face (u 2..4, v 2..4): a cutout face, drawn translucent on both sides;
        // the five solid faces lose their culling (upstream culls only six solid faces).
        FacePartition.Alpha hole = alpha(2, 2, 3, 3, 0);
        byte[] p = FacePartition.classify(positive, hole, false)[0];
        assertEquals(1, count(p, FacePartition.TRANSLUCENT));
        assertEquals(5, count(p, FacePartition.OPAQUE));
        // all_cutout (force_culling): every six-face cube culls, in both partitions.
        p = FacePartition.classify(positive, hole, true)[0];
        assertEquals(1, count(p, FacePartition.TRANSLUCENT_CULL));
        assertEquals(5, count(p, FacePartition.OPAQUE_CULL));

        // A fully transparent face is left out, and the cube no longer has six solid faces.
        FacePartition.Alpha empty = alpha(2, 2, 4, 4, 0);
        p = FacePartition.classify(positive, empty, false)[0];
        assertEquals(1, count(p, FacePartition.NONE));
        assertEquals(5, count(p, FacePartition.OPAQUE));

        // Inverted cubes cull whatever their alpha.
        BakedGeometry negative = cube("{\"origin\":[0,0,0],\"size\":[-2,-2,-2],\"uv\":[4,4]}");
        assertPartitions(FacePartition.classify(negative, opaque, false)[0], FacePartition.OPAQUE_CULL);
        FacePartition.Alpha half = alpha(0, 0, 16, 16, 100);
        assertPartitions(FacePartition.classify(negative, half, false)[0], FacePartition.TRANSLUCENT_CULL);
        assertPartitions(FacePartition.classify(positive, half, false)[0], FacePartition.TRANSLUCENT);
        BakedGeometry oneAxis = cube("{\"origin\":[0,0,0],\"size\":[2,-2,2],\"uv\":[0,0]}");
        assertPartitions(FacePartition.classify(oneAxis, half, false)[0], FacePartition.TRANSLUCENT_CULL);

        // Zero thickness with six solid faces culls (upstream's code, not its docs): the coincident top and bottom are
        // each seen from their own side only.
        BakedGeometry flat = cube("{\"origin\":[0,0,0],\"size\":[2,0,2],\"uv\":[0,0]}");
        assertPartitions(FacePartition.classify(flat, opaque, false)[0], FacePartition.OPAQUE_CULL);
        assertPartitions(FacePartition.classify(flat, half, false)[0], FacePartition.TRANSLUCENT);
        assertPartitions(FacePartition.classify(flat, half, true)[0], FacePartition.TRANSLUCENT_CULL);

        // A missing face: never culled, not even with all_cutout.
        BakedGeometry open = cube(
            "{\"origin\":[0,0,0],\"size\":[2,2,2],\"uv\":{\"north\":{\"uv\":[0,0],\"uv_size\":[2,2]},"
                + "\"south\":{\"uv\":[0,0],\"uv_size\":[2,2]}}}");
        assertPartitions(FacePartition.classify(open, opaque, true)[0], FacePartition.OPAQUE);
    }

    private static int count(byte[] partitions, int partition) {
        int n = 0;
        for (byte b : partitions) {
            n += b == partition ? 1 : 0;
        }
        return n;
    }

    private static void assertPartitions(byte[] partitions, int expected) {
        for (byte b : partitions) {
            assertEquals(expected, b);
        }
    }

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    @TestFactory
    Stream<DynamicTest> everyBuiltinModelPartitionsWithItsTexture() throws IOException {
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(BUILTIN)) {
            manifests = files.filter(
                f -> f.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList());
        }
        return manifests.stream()
            .map(
                m -> DynamicTest.dynamicTest(
                    BUILTIN.relativize(m.getParent())
                        .toString(),
                    () -> {
                        Path dir = m.getParent();
                        ModelSource source = new ModelSource() {

                            @Override
                            public String name() {
                                return dir.toString();
                            }

                            @Override
                            public InputStream open(String path) throws IOException {
                                Path file = dir.resolve(ModelSource.clean(path));
                                return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
                            }
                        };
                        LoadedModel model = ModelLoader.load(source);
                        FacePartition.Alpha alpha;
                        try (InputStream in = source.open(model.defaultTexture())) {
                            assertNotNull(in, "texture");
                            alpha = FacePartition.Alpha.read(in);
                        }
                        FacePartition.Baked baked = FacePartition
                            .bake(model.main, alpha, model.manifest.properties.forceCulling);
                        byte[][] partitions = baked.partitions;
                        int drawn = 0, snapped = 0;
                        int[] per = new int[FacePartition.COUNT];
                        for (int b = 0; b < partitions.length; b++) {
                            assertEquals(model.main.bones[b].quadCount(), partitions[b].length);
                            float[] baseUv = FacePartition.sourceUvs(model.main.bones[b]);
                            for (int q = 0; q < partitions[b].length; q++) {
                                byte x = partitions[b][q];
                                if (x != FacePartition.NONE) {
                                    drawn++;
                                    per[x]++;
                                }
                                for (int k = 0; k < FacePartition.UV_FLOATS; k++) {
                                    float texels = (k % 2 == 0 ? alpha.width : alpha.height)
                                        * Math.abs(baseUv[q * 8 + k] - baked.uvs[b][q * 8 + k]);
                                    if (texels > 0.01f) {
                                        snapped++;
                                        break;
                                    }
                                }
                            }
                        }
                        // Most faces of a real model are textured (vanilla-like skins leave much of their outer layer
                        // empty).
                        assertTrue(drawn > model.main.quadCount() / 3, drawn + " of " + model.main.quadCount());
                        System.out.println(
                            "partitions " + BUILTIN.relativize(dir)
                                + ": quads "
                                + model.main.quadCount()
                                + ", cutout "
                                + per[FacePartition.OPAQUE_CULL]
                                + ", cutout_no_culling "
                                + per[FacePartition.OPAQUE]
                                + ", translucent "
                                + per[FacePartition.TRANSLUCENT]
                                + ", translucent_culling "
                                + per[FacePartition.TRANSLUCENT_CULL]
                                + ", UVs moved by snapping (over 1/100 texel) "
                                + snapped);
                    }));
    }
}

package com.akashic.futura.geo;

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
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.anim.BonePose;
import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ModelSource;

class TranslucentSortTest {

    /** {@code gluPerspective}, column-major. */
    static float[] perspective(float fovyDegrees, float aspect, float near, float far) {
        float f = (float) (1 / Math.tan(Math.toRadians(fovyDegrees) / 2));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1;
        m[14] = 2 * far * near / (near - far);
        return m;
    }

    /** {@code glOrtho}, column-major. */
    static float[] ortho(float l, float r, float b, float t, float n, float f) {
        float[] m = Mat4.identity();
        m[0] = 2 / (r - l);
        m[5] = 2 / (t - b);
        m[10] = -2 / (f - n);
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[14] = -(f + n) / (f - n);
        return m;
    }

    static float[] mul(float[] a, float[] b) {
        float[] out = new float[16];
        Mat4.mul(a, 0, b, 0, out, 0);
        return out;
    }

    private static float[] clipOf(float[] m, float x, float y, float z) {
        float[] c = new float[4];
        for (int r = 0; r < 4; r++) {
            c[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r];
        }
        return c;
    }

    /** Twice the signed screen area of a triangle (positive: counter-clockwise, OpenGL's front face). */
    private static double screenArea(float[] clip, float[] pos, int p) {
        float[] a = clipOf(clip, pos[p], pos[p + 1], pos[p + 2]);
        float[] b = clipOf(clip, pos[p + 3], pos[p + 4], pos[p + 5]);
        float[] c = clipOf(clip, pos[p + 6], pos[p + 7], pos[p + 8]);
        double ax = a[0] / a[3], ay = a[1] / a[3];
        double bx = b[0] / b[3], by = b[1] / b[3];
        double cx = c[0] / c[3], cy = c[1] / c[3];
        return (bx - ax) * (cy - ay) - (cx - ax) * (by - ay);
    }

    private static float ndcDepth(float[] clip, float[] pos, int p) {
        float x = 0, y = 0, z = 0;
        for (int v = 0; v < 4; v++) {
            x += pos[p + v * 3] / 4;
            y += pos[p + v * 3 + 1] / 4;
            z += pos[p + v * 3 + 2] / 4;
        }
        float[] c = clipOf(clip, x, y, z);
        return c[2] / c[3];
    }

    @Test
    void backFacesAreTheOnesOpenGlCulls() {
        Random random = new Random(42);
        float[][] projections = { perspective(70, 16 / 9f, 0.05f, 256), ortho(-4, 4, -3, 3, 0.1f, 100),
            // Minecraft's screen projection: y flipped.
            ortho(0, 854, 480, 0, 1000, 3000) };
        int checked = 0;
        for (int i = 0; i < 3000; i++) {
            float[] projection = projections[i % projections.length];
            boolean gui = i % projections.length == 2;
            float[] view = Mat4.identity();
            if (gui) {
                // The inventory preview: translate into the screen, scale with x mirrored as GuiInventory does.
                Mat4.translate(view, 0, 400, 300, -2000);
                Mat4.scale(view, 0, -30, 30, 30);
            } else {
                Mat4.translate(view, 0, 0, 0, -10);
            }
            Mat4.rotateX(view, 0, random.nextFloat() * 7);
            Mat4.rotateY(view, 0, random.nextFloat() * 7);
            Mat4.rotateZ(view, 0, random.nextFloat() * 7);
            if (random.nextBoolean()) {
                Mat4.scale(view, 0, -1, 1, 1);
            }
            float[] clip = mul(projection, view);
            float[] eye = TranslucentSort.eye(clip);
            float[] pos = new float[12];
            for (int k = 0; k < 9; k++) {
                pos[k] = random.nextFloat() * 2 - 1;
            }
            double area = screenArea(clip, pos, 0);
            if (Math.abs(area) < 1e-6) {
                continue;
            }
            assertEquals(area <= 0, TranslucentSort.backFace(pos, 0, eye), "case " + i);
            checked++;
        }
        assertTrue(checked > 2900);
    }

    @Test
    void depthKeysSortFarthestFirst() {
        float[] depths = { 1, 0.99f, 0.3f, 0, -0.5f, -1 };
        for (int i = 0; i + 1 < depths.length; i++) {
            assertTrue(
                Integer.compareUnsigned(TranslucentSort.depthKey(depths[i]), TranslucentSort.depthKey(depths[i + 1]))
                    < 0,
                depths[i] + " before " + depths[i + 1]);
        }
        assertEquals(TranslucentSort.depthKey(0f), TranslucentSort.depthKey(-0f));
        assertEquals(0xFFFFFFFF, TranslucentSort.depthKey(Float.NaN));
        assertEquals(0xFFFFFFFF, TranslucentSort.depthKey(Float.POSITIVE_INFINITY));
        assertTrue(Integer.compareUnsigned(TranslucentSort.depthKey(-1e30f), 0xFFFFFFFF) < 0);
    }

    private static BakedGeometry geometry(String bones) throws IOException {
        return GeoBaker.bake(
            FormatGson.create()
                .fromJson(
                    "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"texture_width\":16,"
                        + "\"texture_height\":16},\"bones\":["
                        + bones
                        + "]}]}",
                    GeoModel.class));
    }

    private static FacePartition.Alpha halfAlpha() {
        byte[] a = new byte[16 * 16];
        Arrays.fill(a, (byte) 100);
        return new FacePartition.Alpha(16, 16, a);
    }

    /** Two thin translucent panels, 8 pixels apart on z, in two bones; a cube between them in a third. */
    private static final String PANELS = "{\"name\":\"front\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-4,0,4],"
        + "\"size\":[8,8,1],\"uv\":[0,0]}]},"
        + "{\"name\":\"back\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-4,0,-5],\"size\":[8,8,1],\"uv\":[0,0]}]},"
        + "{\"name\":\"middle\",\"parent\":\"back\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,3,-1],"
        + "\"size\":[2,2,2],\"uv\":[0,0]}]}";

    /** The bone of each drawn quad, farthest first. */
    private static String drawnBones(BakedGeometry g, TranslucentSort sort) {
        StringBuilder out = new StringBuilder();
        String last = null;
        for (int i = 0; i < sort.drawn(); i++) {
            String name = g.bones[sort.bone(sort.order(i))].name;
            if (!name.equals(last)) {
                out.append(out.length() == 0 ? "" : " ")
                    .append(name);
                last = name;
            }
        }
        return out.toString();
    }

    private static float[] camera(float yawDegrees, float distance) {
        float[] view = Mat4.identity();
        Mat4.translate(view, 0, 0, -0.25f, -distance);
        Mat4.rotateY(view, 0, (float) Math.toRadians(yawDegrees));
        return mul(perspective(70, 1, 0.05f, 100), view);
    }

    @Test
    void quadsOfDifferentBonesSortBackToFront() throws IOException {
        BakedGeometry g = geometry(PANELS);
        byte[][] partitions = FacePartition.classify(g, halfAlpha(), false);
        TranslucentSort sort = new TranslucentSort(g, partitions, null);
        assertEquals(18, sort.count);
        PoseMatrices pose = new PoseMatrices(g);

        // From +z (front panel nearest): back panel, middle cube, front panel.
        float[] clip = camera(0, 3);
        assertEquals(18, sort.sort(pose, clip));
        assertEquals("back middle front", drawnBones(g, sort));
        assertSorted(sort, clip);
        // From -z: the other way round.
        clip = camera(180, 3);
        sort.sort(pose, clip);
        assertEquals("front middle back", drawnBones(g, sort));
        assertSorted(sort, clip);

        // A hidden bone hides its quads (here with its child).
        BonePose hidden = new BonePose(g.bones.length);
        int back = g.indexOf("back");
        for (int k = 6; k < 9; k++) {
            hidden.values[back * BonePose.FLOATS + k] = 0;
        }
        pose.compute(hidden);
        sort.sort(pose, clip);
        assertEquals("front", drawnBones(g, sort));
        assertEquals(6, sort.drawn());
    }

    @Test
    void culledTranslucentQuadsDropTheirBackFaces() throws IOException {
        // The outline shell: negative size, culled; from outside only its far faces are drawn.
        BakedGeometry g = geometry(
            "{\"name\":\"shell\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[2,10,2],\"size\":[-4,-4,-4],"
                + "\"uv\":[0,0]}]}");
        byte[][] partitions = FacePartition.classify(g, halfAlpha(), false);
        TranslucentSort sort = new TranslucentSort(g, partitions, null);
        assertEquals(6, sort.count);
        assertTrue(sort.culled(0));
        PoseMatrices pose = new PoseMatrices(g);
        float[] view = Mat4.identity();
        Mat4.translate(view, 0, 0, -0.5f, -3);
        Mat4.rotateX(view, 0, 0.4f);
        Mat4.rotateY(view, 0, 0.7f);
        float[] clip = mul(perspective(70, 1, 0.05f, 100), view);
        assertEquals(3, sort.sort(pose, clip));
        for (int i = 0; i < sort.drawn(); i++) {
            assertTrue(screenArea(clip, sort.positions, sort.order(i) * 12) > 0, "front face");
            // The far faces: their normals (pointing inward) face the camera.
        }
        assertSorted(sort, clip);
    }

    @Test
    void tiesKeepEmissionOrder() throws IOException {
        // A flat translucent panel: its top and bottom faces share a centre; the bottom one (emitted after) is drawn
        // last, from above and from below, as upstream's stable key does.
        BakedGeometry g = geometry(
            "{\"name\":\"panel\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-4,8,-4],\"size\":[8,0,8],"
                + "\"uv\":[0,0]}]}");
        TranslucentSort sort = new TranslucentSort(g, FacePartition.classify(g, halfAlpha(), false), null);
        PoseMatrices pose = new PoseMatrices(g);
        for (float pitch : new float[] { 0.6f, -0.6f }) {
            float[] view = Mat4.identity();
            Mat4.translate(view, 0, 0, 0, -3);
            Mat4.rotateX(view, 0, pitch);
            Mat4.translate(view, 0, 0, -0.5f, 0);
            float[] clip = mul(perspective(70, 1, 0.05f, 100), view);
            sort.sort(pose, clip);
            int up = -1, down = -1;
            for (int i = 0; i < sort.drawn(); i++) {
                float ny = g.bones[0].quads[sort.order(i) * BakedGeometry.QUAD_FLOATS + 21];
                if (ny > 0.5f) {
                    up = i;
                } else if (ny < -0.5f) {
                    down = i;
                }
            }
            assertTrue(up >= 0 && down == up + 1, "up " + up + ", down " + down);
        }
    }

    private static void assertSorted(TranslucentSort sort, float[] clip) {
        float last = Float.POSITIVE_INFINITY;
        for (int i = 0; i < sort.drawn(); i++) {
            float d = ndcDepth(clip, sort.positions, sort.order(i) * 12);
            assertTrue(d <= last + 1e-4f * Math.max(1, Math.abs(d)), "quad " + i + " depth " + d + " after " + last);
            last = d;
        }
    }

    @Test
    void normalsFollowTheBoneWithItsInverseTranspose() {
        float[] m = Mat4.identity();
        Mat4.rotateZ(m, 0, 0.5f);
        Mat4.scale(m, 0, 2, 1, 1);
        float[] n = new float[9];
        TranslucentSort.normalMatrix(m, 0, n, 0);
        // A plane x = y in local space: normal (1, -1, 0). Its points transform with m; the normal must stay
        // perpendicular to them.
        float[] a = new float[3], b = new float[3];
        Mat4.transformPoint(m, 0, 1, 1, 0, a);
        Mat4.transformPoint(m, 0, 0, 0, 1, b);
        float nx = n[0] - n[3], ny = n[1] - n[4], nz = n[2] - n[5];
        assertEquals(0, nx * a[0] + ny * a[1] + nz * a[2], 1e-5f);
        assertEquals(0, nx * b[0] + ny * b[1] + nz * b[2], 1e-5f);
        // Mirrored: still the outward direction (the sign of the determinant is undone).
        float[] mirror = Mat4.identity();
        Mat4.scale(mirror, 0, -1, 1, 1);
        TranslucentSort.normalMatrix(mirror, 0, n, 0);
        assertEquals(-1, n[0], 1e-6f);
        assertEquals(1, n[4], 1e-6f);
    }

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    @TestFactory
    Stream<DynamicTest> builtinModelsSortTheirTranslucentQuads() throws IOException {
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(BUILTIN.resolve("wine_fox"))) {
            manifests = files.filter(
                f -> f.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList());
        }
        assertFalse(manifests.isEmpty());
        return manifests.stream()
            .map(
                m -> DynamicTest.dynamicTest(
                    BUILTIN.relativize(m.getParent())
                        .toString(),
                    () -> check(m.getParent())));
    }

    private static void check(Path dir) throws IOException {
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
        FacePartition.Baked baked = FacePartition.bake(model.main, alpha, model.manifest.properties.forceCulling);
        TranslucentSort sort = new TranslucentSort(model.main, baked.partitions, baked.uvs);
        PoseMatrices pose = new PoseMatrices(model.main);
        StringBuilder report = new StringBuilder("translucent " + BUILTIN.relativize(dir) + ": " + sort.count);
        int culled = 0;
        for (int q = 0; q < sort.count; q++) {
            culled += sort.culled(q) ? 1 : 0;
        }
        report.append(" (culled partition ")
            .append(culled)
            .append("), drawn per view");
        long nanos = 0;
        int runs = 0;
        for (float yaw = 0; yaw < 360; yaw += 45) {
            float[] view = Mat4.identity();
            Mat4.translate(view, 0, 0, -1, -3);
            Mat4.rotateX(view, 0, 0.3f);
            Mat4.rotateY(view, 0, (float) Math.toRadians(yaw));
            float[] clip = mul(perspective(70, 16 / 9f, 0.05f, 256), view);
            long start = System.nanoTime();
            int drawn = sort.sort(pose, clip);
            nanos += System.nanoTime() - start;
            runs++;
            assertTrue(drawn <= sort.count && drawn >= sort.count - culled);
            assertSorted(sort, clip);
            float[] eye = TranslucentSort.eye(clip);
            for (int i = 0; i < drawn; i++) {
                int q = sort.order(i);
                if (sort.culled(q)) {
                    assertFalse(TranslucentSort.backFace(sort.positions, q * 12, eye));
                }
                float[] n = sort.normals;
                float len = n[q * 3] * n[q * 3] + n[q * 3 + 1] * n[q * 3 + 1] + n[q * 3 + 2] * n[q * 3 + 2];
                assertEquals(1, len, 1e-3f);
            }
            report.append(' ')
                .append(drawn);
        }
        report.append(String.format(java.util.Locale.ROOT, ", sort %.3f ms", nanos / 1e6 / runs));
        System.out.println(report);
    }
}

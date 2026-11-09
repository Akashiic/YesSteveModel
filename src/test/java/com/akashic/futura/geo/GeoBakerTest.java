package com.akashic.futura.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

class GeoBakerTest {

    private static final float EPS = 1e-5f;

    private static BakedGeometry bake(String json) throws IOException {
        return GeoBaker.bake(
            FormatGson.create()
                .fromJson(json, GeoModel.class));
    }

    @Test
    void boxUvFollowsTheVanillaLayout() throws IOException {
        // The vanilla body: 8x12x4 at uv (16, 16) on a 64x64 texture.
        BakedGeometry geo = bake(
            "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"texture_width\":64,"
                + "\"texture_height\":64},\"bones\":[{\"name\":\"body\",\"pivot\":[0,24,0],"
                + "\"cubes\":[{\"origin\":[-4,12,-2],\"size\":[8,12,4],\"uv\":[16,16]}]}]}]}");
        assertEquals(1, geo.bones.length);
        BakedGeometry.Bone body = geo.bones[0];
        assertEquals(6, body.quadCount());
        assertArrayEquals(new float[] { 0, 1.5f, 0 }, body.pivot);
        // Third quad is north: u from 16+4 to 16+4+8, v from 16+4 to 16+4+12, normal -z.
        int north = 2 * BakedGeometry.QUAD_FLOATS;
        float[] q = body.quads;
        assertEquals(28 / 64f, q[north + 3], EPS); // first vertex takes u2 when not mirrored
        assertEquals(20 / 64f, q[north + 4], EPS);
        assertEquals(20 / 64f, q[north + 5 + 3], EPS);
        assertEquals(32 / 64f, q[north + 10 + 4], EPS);
        assertEquals(-1, q[north + 22], EPS);
        // Every vertex lies on the cube: x in [-0.25, 0.25] (negated), y in [0.75, 1.5], z in [-0.125, 0.125].
        for (int f = 0; f < 6; f++) {
            for (int v = 0; v < 4; v++) {
                int o = f * BakedGeometry.QUAD_FLOATS + v * 5;
                assertTrue(Math.abs(q[o]) <= 0.25f + EPS);
                assertTrue(q[o + 1] >= 0.75f - EPS && q[o + 1] <= 1.5f + EPS);
                assertTrue(Math.abs(q[o + 2]) <= 0.125f + EPS);
            }
        }
    }

    @Test
    void cubeRotationIsBakedAroundItsPivot() throws IOException {
        // A 1-pixel cube at x = 1..2 turned 90 degrees around Y at the origin ends up at z = 1..2.
        BakedGeometry geo = bake(
            "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"b\",\"cubes\":[{\"origin\":[1,0,0],"
                + "\"size\":[1,1,1],\"uv\":[0,0],\"pivot\":[0,0,0],\"rotation\":[0,90,0]}]}]}]}");
        float[] q = geo.bones[0].quads;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int f = 0; f < geo.bones[0].quadCount(); f++) {
            for (int v = 0; v < 4; v++) {
                float z = q[f * BakedGeometry.QUAD_FLOATS + v * 5 + 2];
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        assertEquals(1 / 16f, Math.abs(minZ) < Math.abs(maxZ) ? Math.abs(minZ) : Math.abs(maxZ), EPS);
        assertEquals(2 / 16f, Math.max(Math.abs(minZ), Math.abs(maxZ)), EPS);
    }

    @Test
    void parentsComeBeforeChildrenWhateverTheFileOrder() throws IOException {
        BakedGeometry geo = bake(
            "{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"hand\",\"parent\":\"arm\"},"
                + "{\"name\":\"arm\",\"parent\":\"root\"},{\"name\":\"root\"},{\"name\":\"loop\",\"parent\":\"loop\"}]}]}");
        for (int i = 0; i < geo.bones.length; i++) {
            assertTrue(geo.bones[i].parent < i, geo.bones[i].name);
        }
        assertEquals(geo.indexOf("arm"), geo.bones[geo.indexOf("hand")].parent);
        assertEquals(-1, geo.bones[geo.indexOf("loop")].parent);
        assertEquals(2, geo.roots.length);
    }

    @Test
    void theDefaultModelBakes() throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/default/"));
        GeoModel raw;
        try (Reader reader = new InputStreamReader(
            GeoBakerTest.class.getResourceAsStream("/assets/futura3d/builtin/default/models/main.json"),
            StandardCharsets.UTF_8)) {
            raw = FormatGson.create()
                .fromJson(reader, GeoModel.class);
        }
        assertEquals(raw.minecraftGeometry.get(0).bones.size(), model.main.bones.length);
        assertTrue(model.main.cubeCount > 50, "cubes: " + model.main.cubeCount);
        assertTrue(model.main.indexOf("Head") >= 0);
        assertTrue(model.main.indexOf("RightHandLocator") >= 0);
        // A player-sized model: from the feet to about two blocks, before the 0.7 scale.
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (BakedGeometry.Bone bone : model.main.bones) {
            for (int f = 0; f < bone.quadCount(); f++) {
                for (int v = 0; v < 4; v++) {
                    float y = bone.quads[f * BakedGeometry.QUAD_FLOATS + v * 5 + 1];
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue(minY > -0.2f && minY < 0.2f, "feet at " + minY);
        assertTrue(maxY > 1.5f && maxY < 3.5f, "top at " + maxY);
        assertTrue(model.animations.containsKey("main"));
        assertTrue(!model.textures.isEmpty());
    }

    private static void assertArrayEquals(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], EPS, "index " + i);
        }
    }
}

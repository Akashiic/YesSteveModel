package com.akashic.futura.model.legacy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.akashic.futura.anim.OriginVersion;
import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.MemoryModelSource;
import com.akashic.futura.model.ModelLoader;

class LegacyYsmTest {

    private static final int MAX_FILE = 16 << 20;
    private static final long MAX_TOTAL = 64L << 20;

    @Test
    void probesTheHeaders() {
        assertEquals(LegacyYsm.Kind.V1_RAW, LegacyYsm.probe(new byte[] { 'Y', 'S', 'G', 'P', 0, 0, 0, 1 }));
        assertEquals(LegacyYsm.Kind.V2_RAW, LegacyYsm.probe(new byte[] { 'Y', 'S', 'G', 'P', 0, 0, 0, 2 }));
        assertEquals(LegacyYsm.Kind.UNSUPPORTED, LegacyYsm.probe(new byte[] { 'Y', 'S', 'G', 'P', 0, 0, 0, 4 }));
        assertEquals(
            LegacyYsm.Kind.V3_ENCRYPTED,
            LegacyYsm.probe(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P', 0, 3 }));
        assertEquals(LegacyYsm.Kind.UNSUPPORTED, LegacyYsm.probe("PK\3\4zip!".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void readsVersionOneAndTwoBundles() throws IOException {
        Map<String, byte[]> original = LegacyTestSupport.builtin("misc/1_alex");
        for (int version = 1; version <= 2; version++) {
            byte[] bundle = LegacyTestSupport.bundle(version, original, 42 + version);
            Map<String, byte[]> read = LegacyYsm.read(bundle, null, MAX_FILE, MAX_TOTAL);
            assertEquals(original.keySet(), read.keySet(), "version " + version);
            for (Map.Entry<String, byte[]> f : original.entrySet()) {
                assertArrayEquals(f.getValue(), read.get(f.getKey()), f.getKey());
            }
            LoadedModel model = ModelLoader.load(new MemoryModelSource("bundle", read));
            assertTrue(
                model.name()
                    .startsWith("Alex"));
        }
    }

    @Test
    void rejectsTamperedOrOversizedBundles() throws IOException {
        byte[] bundle = LegacyTestSupport.bundle(2, LegacyTestSupport.builtin("misc/1_alex"), 7);
        bundle[bundle.length - 1] ^= 1;
        assertThrows(IOException.class, () -> LegacyYsm.read(bundle, null, MAX_FILE, MAX_TOTAL));
        byte[] good = LegacyTestSupport.bundle(1, LegacyTestSupport.builtin("misc/1_alex"), 7);
        assertThrows(IOException.class, () -> LegacyYsm.read(good, null, 100, MAX_TOTAL));
        assertThrows(IOException.class, () -> LegacyYsm.read(good, null, MAX_FILE, 1000));
        // v3 without a decoder
        assertThrows(
            IOException.class,
            () -> LegacyYsm.read(
                new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P', 0, 3 },
                null,
                MAX_FILE,
                MAX_TOTAL));
    }

    /** The 1.x layout (no ysm.json): the manifest LegacyManifestBuilder would build. */
    @Test
    void readsTheOneXLayout() throws IOException {
        Map<String, byte[]> alex = LegacyTestSupport.builtin("misc/1_alex");
        Map<String, byte[]> files = new TreeMap<>();
        files.put("main.json", alex.get("models/main.json"));
        files.put("arm.json", alex.get("models/arm.json"));
        for (Map.Entry<String, byte[]> f : alex.entrySet()) {
            if (f.getKey()
                .startsWith("textures/")) {
                files.put(
                    f.getKey()
                        .substring("textures/".length()),
                    f.getValue());
            }
        }
        files.put(
            "info.json",
            "{\"name\":\"Old Alex\",\"tips\":\"1.x\",\"authors\":[\"A\",\"B\"],\"license\":\"CC0\",\"free\":true,\"extra_animation_names\":[\"wave\",\"sit\"]}"
                .getBytes(StandardCharsets.UTF_8));
        byte[] bundle = LegacyTestSupport.bundle(1, files, 3);
        Map<String, byte[]> read = LegacyYsm.read(bundle, null, MAX_FILE, MAX_TOTAL);
        assertNotNull(read.get("ysm.json"));
        LoadedModel model = ModelLoader.load(new MemoryModelSource("old", read));
        assertEquals("Old Alex", model.name());
        assertEquals("CC0", model.manifest.metadata.license.type);
        assertEquals(2, model.manifest.metadata.authors.size());
        assertEquals("sit", model.manifest.properties.extraAnimation.get("extra1"));
        assertTrue(model.manifest.properties.free);
        assertEquals("gsl.png", model.textures.get(0));
    }

    /** The synthetic v32 model of native/test/native_test.cc, exported by the native code, through our loader. */
    @Test
    void loadsTheNativeExportOfALegacyModel() throws IOException {
        byte[] export = LegacyTestSupport.syntheticExport();
        LegacyArchive archive = LegacyArchive.parse(export, MAX_FILE);
        assertEquals(32, archive.innerVersion);
        Map<String, byte[]> files = LegacyYsm.read(
            new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P', 0, 3 },
            file -> export,
            MAX_FILE,
            MAX_TOTAL);
        LoadedModel model = ModelLoader.load(new MemoryModelSource("synthetic", files));
        assertEquals("Synthetic", model.name());
        assertEquals("2.4.2-snapshot-32", model.manifest.properties.originVer);
        assertEquals(OriginVersion.FILE_HAND_LOOP, OriginVersion.fromModVersion(model.manifest.properties.originVer));
        assertTrue(model.manifest.properties.mergeMultilineExpr);
        assertEquals(0.9f, model.heightScale, 1e-6);

        // the quad comes back exactly as the legacy container stored it (blocks, x negated, normalised UVs)
        BakedGeometry.Bone root = model.main.bones[model.main.indexOf("root")];
        assertEquals(1, root.quadCount());
        assertEquals(1, root.cubeShapes.length);
        assertEquals(BakedGeometry.SHAPE_OPEN, root.cubeShapes[0]);
        float[] expected = { -0.0625f, 0, 0.25f, 0, 0, -0.125f, 0, 0.25f, 0.125f, 0, -0.125f, 0.0625f, 0.25f, 0.125f,
            0.25f, -0.0625f, 0.0625f, 0.25f, 0, 0.25f, 0, 0, 1 };
        assertArrayEquals(expected, root.quads, 0f);
        assertArrayEquals(new float[] { -1 / 16f, 2 / 16f, 3 / 16f }, root.pivot, 1e-7f);
        assertEquals(0.5f, root.rotation[0], 1e-6f);

        // animations in ticks, controllers, functions, languages
        com.akashic.futura.format.pojo.animation.Animation idle = null;
        for (com.akashic.futura.format.pojo.animation.Animation a : model.animations.get("main").animations) {
            if (a.animationName.equals("idle")) {
                idle = a;
            }
        }
        assertNotNull(idle);
        assertEquals(20f, idle.animationLength, 1e-4f);
        assertEquals(2, idle.boneAnimations.get(0).rotationKeyFrames.keyFrames.size());
        assertNotNull(model.controllers.get("controller.animation.synthetic"));
        assertEquals("v.x = 1;", model.functions.get("hello@player_init"));
        assertNotNull(files.get("lang/en_us.json"));

        // the RGBA texture became a PNG of the same pixel
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(files.get("textures/skin.png")));
        assertEquals(1, skin.getWidth());
        assertEquals(0xFF102030, skin.getRGB(0, 0));
    }

    @Test
    void rejectsBrokenExports() {
        assertThrows(IOException.class, () -> LegacyArchive.parse("F3DL".getBytes(StandardCharsets.US_ASCII), 100));
        assertThrows(IOException.class, () -> LegacyArchive.parse(new byte[] { 1, 2, 3, 4, 0, 0, 0, 1 }, 100));
    }
}

package com.akashic.futura.net;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.model.ModelSource;

class ModelLimitsTest {

    static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    /** The transferable files of a folder, by clean path. */
    static Map<String, byte[]> read(Path dir) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile)
                .collect(Collectors.toList())) {
                String rel = dir.relativize(p)
                    .toString()
                    .replace('\\', '/');
                if (ModelLimits.transferable(rel)) {
                    files.put(rel, Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    static ModelSource source(Map<String, byte[]> files) {
        return new ModelSource() {

            @Override
            public String name() {
                return "test";
            }

            @Override
            public java.io.InputStream open(String path) throws IOException {
                byte[] b = files.get(ModelSource.clean(path));
                return b == null ? null : new java.io.ByteArrayInputStream(b);
            }
        };
    }

    /** The default limits accept every built-in model (the format's real models). */
    @TestFactory
    Stream<DynamicTest> everyBuiltinModelIsWithinTheLimits() throws IOException {
        List<Path> dirs;
        try (Stream<Path> walk = Files.walk(BUILTIN)) {
            dirs = walk.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .map(Path::getParent)
                .sorted()
                .collect(Collectors.toList());
        }
        ModelLimits limits = new ModelLimits();
        return dirs.stream()
            .map(
                d -> DynamicTest.dynamicTest(
                    BUILTIN.relativize(d)
                        .toString(),
                    () -> {
                        Map<String, byte[]> files = read(d);
                        limits.validate(files, source(files));
                    }));
    }

    @Test
    void transferableKinds() {
        org.junit.jupiter.api.Assertions.assertTrue(ModelLimits.transferable("textures/a.PNG"));
        org.junit.jupiter.api.Assertions.assertTrue(ModelLimits.transferable("functions/x@player_init.molang"));
        org.junit.jupiter.api.Assertions.assertFalse(ModelLimits.transferable("model.ysm"));
        org.junit.jupiter.api.Assertions.assertFalse(ModelLimits.transferable("source.bbmodel"));
        org.junit.jupiter.api.Assertions.assertFalse(ModelLimits.transferable("LICENSE"));
        org.junit.jupiter.api.Assertions.assertFalse(ModelLimits.transferable("dir/.json"));
        org.junit.jupiter.api.Assertions.assertFalse(ModelLimits.transferable("run.exe"));
    }

    @Test
    void refusesDeepOrHugeJson() {
        ModelLimits limits = new ModelLimits();
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            deep.append('[');
        }
        assertThrows(
            IOException.class,
            () -> limits.checkFile(
                "a.json",
                deep.toString()
                    .getBytes()));
        StringBuilder longString = new StringBuilder("{\"x\":\"");
        for (int i = 0; i <= limits.maxJsonString; i++) {
            longString.append('q');
        }
        longString.append("\"}");
        assertThrows(
            IOException.class,
            () -> limits.checkFile(
                "a.json",
                longString.toString()
                    .getBytes()));
        limits.maxJsonTokens = 10;
        assertThrows(IOException.class, () -> limits.checkFile("a.json", "[1,2,3,4,5,6,7,8,9,10,11]".getBytes()));
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    @Test
    void readsImageSizesFromHeaders() throws IOException {
        ModelLimits limits = new ModelLimits();
        limits.maxImageSide = 64;
        limits.checkFile("a.png", png(64, 32));
        assertThrows(IOException.class, () -> limits.checkFile("a.png", png(65, 8)));
        assertThrows(IOException.class, () -> limits.checkFile("a.png", "not a png at all, really".getBytes()));
        limits.checkFile("a.jpg", jpeg(64, 64));
        assertThrows(IOException.class, () -> limits.checkFile("a.jpg", jpeg(8, 100)));
    }

    @Test
    void readsOnlySpecTwo() throws IOException {
        ModelLimits.checkSpec("{\"spec\": 2, \"files\": {}}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ModelLimits.checkSpec("{\"spec\": 1}".getBytes()));
        assertThrows(IOException.class, () -> ModelLimits.checkSpec("{}".getBytes()));
        assertThrows(IOException.class, () -> ModelLimits.checkSpec("[".getBytes()));
    }

    @Test
    void countsBonesAndCubes() throws IOException {
        Map<String, byte[]> files = read(BUILTIN.resolve("default"));
        ModelLimits limits = new ModelLimits();
        limits.maxCubes = 10;
        assertThrows(IOException.class, () -> limits.validate(files, source(files)));
        ModelLimits fewFiles = new ModelLimits();
        fewFiles.maxFiles = 3;
        assertThrows(IOException.class, () -> fewFiles.validate(files, source(files)));
    }

    /** Projectile and vehicle replacements count toward the bone, cube and animation totals. */
    @Test
    void countsReplacementTargets() throws IOException {
        com.akashic.futura.model.LoadedModel model = com.akashic.futura.model.ModelLoader
            .load(com.akashic.futura.model.BuiltinIndex.source("default"));
        int playerBones = model.main.bones.length + model.arm.bones.length;
        int playerCubes = model.main.cubeCount + model.arm.cubeCount;
        int replacementBones = 0;
        int replacementCubes = 0;
        for (com.akashic.futura.model.ReplacementTarget t : model.replacements()) {
            replacementBones += t.geometry.bones.length;
            replacementCubes += t.geometry.cubeCount;
        }
        org.junit.jupiter.api.Assertions.assertTrue(replacementBones > 0 && replacementCubes > 0);
        ModelLimits bones = new ModelLimits();
        bones.maxBones = playerBones + replacementBones - 1;
        assertThrows(IOException.class, () -> bones.checkModel(model));
        bones.maxBones = playerBones + replacementBones;
        bones.checkModel(model);
        ModelLimits cubes = new ModelLimits();
        cubes.maxCubes = playerCubes + replacementCubes - 1;
        assertThrows(IOException.class, () -> cubes.checkModel(model));
        int animations = 0;
        for (com.akashic.futura.format.pojo.animation.AnimationFile f : model.animations.values()) {
            animations += f.animations.size();
        }
        ModelLimits anims = new ModelLimits();
        anims.maxAnimations = animations;
        assertThrows(IOException.class, () -> anims.checkModel(model));
    }
}

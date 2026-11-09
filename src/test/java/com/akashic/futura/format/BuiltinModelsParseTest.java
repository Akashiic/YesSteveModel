package com.akashic.futura.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.format.pojo.controller.AnimationControllerFile;
import com.akashic.futura.format.pojo.manifest.ModelManifest;
import com.akashic.futura.format.pojo.manifest.models.PBRTextureSet;
import com.akashic.futura.format.pojo.model.Bone;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.google.gson.Gson;

/** Every built-in model's manifest, geometry, animations and controllers parse with the ported POJOs. */
class BuiltinModelsParseTest {

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    @TestFactory
    Stream<DynamicTest> everyBuiltinModelParses() throws IOException {
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(BUILTIN)) {
            manifests = files.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList());
        }
        assertTrue(manifests.size() >= 20, "built-in models found: " + manifests.size());
        return manifests.stream()
            .map(
                m -> DynamicTest.dynamicTest(
                    BUILTIN.relativize(m.getParent())
                        .toString(),
                    () -> parseModel(m)));
    }

    private static void parseModel(Path manifestPath) throws IOException {
        Path dir = manifestPath.getParent();
        Gson gson = FormatGson.create();
        ModelManifest manifest = read(gson, manifestPath, ModelManifest.class);
        manifest.normalize();
        assertNotNull(manifest.metadata, "metadata");
        assertFalse(manifest.metadata.license.type.isEmpty(), "licence type");

        Map<String, String> models = manifest.files.player.model;
        assertTrue(models.containsKey("main"), "main model");
        assertTrue(models.containsKey("arm"), "arm model");
        for (Map.Entry<String, String> model : models.entrySet()) {
            GeoModel geo = read(gson, dir.resolve(model.getValue()), GeoModel.class);
            assertNotNull(geo.minecraftGeometry, model.getKey() + ": minecraft:geometry");
            assertFalse(geo.minecraftGeometry.isEmpty(), model.getKey() + ": no geometry");
            List<Bone> bones = geo.minecraftGeometry.get(0).bones;
            assertFalse(bones.isEmpty(), model.getKey() + ": no bones");
            for (Bone bone : bones) {
                assertNotNull(bone.name, model.getKey() + ": bone without a name");
            }
        }

        Gson animationGson = FormatGson.create(manifest.properties.mergeMultilineExpr);
        for (Map.Entry<String, String> animation : manifest.files.player.animation.entrySet()) {
            AnimationFile file = read(animationGson, dir.resolve(animation.getValue()), AnimationFile.class);
            assertNotNull(file.animations, animation.getKey());
        }
        // A model may leave out any animation: the engine falls back to the default model's one of the same name
        // (alex and default_controllers have no idle of their own). So only the default model must have the basics.
        if (dir.getFileName()
            .toString()
            .equals("default")) {
            AnimationFile main = read(
                animationGson,
                dir.resolve(manifest.files.player.animation.get("main")),
                AnimationFile.class);
            for (String basic : new String[] { "idle", "walk", "run", "sneak", "jump", "sleep", "death" }) {
                assertTrue(
                    main.animations.stream()
                        .anyMatch(a -> a.animationName.equals(basic)),
                    "default model has " + basic);
            }
        }

        for (String controller : manifest.files.player.animationControllers) {
            AnimationControllerFile file = read(gson, dir.resolve(controller), AnimationControllerFile.class);
            assertNotNull(file.animationControllers, controller);
        }

        List<String> missing = new ArrayList<>();
        for (PBRTextureSet texture : manifest.files.player.texture) {
            if (!Files.isRegularFile(dir.resolve(texture.uv))) {
                missing.add(texture.uv);
            }
        }
        assertEquals(new ArrayList<String>(), missing, "missing textures");
        assertFalse(manifest.files.player.texture.isEmpty(), "textures");
    }

    private static <T> T read(Gson gson, Path file, Class<T> type) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing file " + file);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T value = gson.fromJson(reader, type);
            assertNotNull(value, "empty file " + file);
            return value;
        }
    }
}

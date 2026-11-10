package com.akashic.futura.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.format.pojo.controller.AnimationController;
import com.akashic.futura.format.pojo.controller.AnimationControllerFile;
import com.akashic.futura.format.pojo.manifest.ModelManifest;
import com.akashic.futura.format.pojo.manifest.models.PBRTextureSet;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.akashic.futura.format.pojo.model.GeometryDescription;
import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.geo.GeoBaker;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Reads a model in the open authoring format ({@code ysm.json} spec 2) from a {@link ModelSource} and bakes its
 * geometry. The 1.x layout without {@code ysm.json} is not read yet.
 */
public final class ModelLoader {

    private ModelLoader() {}

    public static LoadedModel load(ModelSource source) throws IOException {
        Gson gson = FormatGson.create();
        ModelManifest manifest = read(gson, source, "ysm.json", ModelManifest.class);
        manifest.normalize();
        Map<String, String> models = manifest.files.player.model;
        if (!models.containsKey("main") || !models.containsKey("arm")) {
            throw new IOException(source.name() + ": files.player.model needs main and arm");
        }
        GeoModel mainRaw = read(gson, source, models.get("main"), GeoModel.class);
        BakedGeometry main = GeoBaker.bake(mainRaw);
        BakedGeometry arm = GeoBaker.bake(read(gson, source, models.get("arm"), GeoModel.class));

        Gson animationGson = FormatGson.create(manifest.properties.mergeMultilineExpr);
        Map<String, AnimationFile> animations = new LinkedHashMap<>();
        for (Map.Entry<String, String> set : manifest.files.player.animation.entrySet()) {
            animations.put(set.getKey(), read(animationGson, source, set.getValue(), AnimationFile.class));
        }

        // Controllers of every file, merged by name (a later file replaces an earlier one, as upstream's assembler).
        Map<String, AnimationController> controllers = new LinkedHashMap<>();
        for (String file : manifest.files.player.animationControllers) {
            if (file == null || file.trim()
                .isEmpty()) {
                continue;
            }
            AnimationControllerFile controllerFile = read(gson, source, file, AnimationControllerFile.class);
            if (controllerFile.animationControllers != null) {
                for (Map.Entry<String, AnimationController> e : new TreeMap<>(controllerFile.animationControllers)
                    .entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        controllers.put(e.getKey(), e.getValue());
                    }
                }
            }
        }

        List<String> listing = source.list();
        String functionPath = blank(manifest.files.functionPath) ? "functions" : manifest.files.functionPath;
        Map<String, String> functions = new LinkedHashMap<>();
        for (String path : ModelSource.filesUnder(listing, functionPath, ".molang")) {
            functions.put(relativeName(functionPath, path), readText(source, path));
        }
        @SuppressWarnings("deprecation")
        String playerSoundPath = manifest.files.player.soundPath;
        String soundPath = !blank(manifest.files.soundPath) ? manifest.files.soundPath
            : !blank(playerSoundPath) ? playerSoundPath : "sounds";
        Map<String, String> sounds = new LinkedHashMap<>();
        for (String path : ModelSource.filesUnder(listing, soundPath, "")) {
            sounds.putIfAbsent(relativeName(soundPath, path), path);
        }

        List<String> textures = new ArrayList<>();
        for (PBRTextureSet texture : manifest.files.player.texture) {
            if (texture != null && texture.uv != null && !texture.uv.isEmpty()) {
                textures.add(ModelSource.clean(texture.uv));
            }
        }
        if (textures.isEmpty()) {
            throw new IOException(source.name() + ": no texture");
        }

        // Upstream: a manifest left at the 0.7 default takes the scale from the geometry's description.
        float heightScale = manifest.properties.heightScale;
        float widthScale = manifest.properties.widthScale;
        GeometryDescription desc = mainRaw.minecraftGeometry.get(0).description;
        if (heightScale == 0.7f && desc != null) {
            heightScale = desc.ysmHeightScale;
        }
        if (widthScale == 0.7f && desc != null) {
            widthScale = desc.ysmWidthScale;
        }
        return new LoadedModel(
            source,
            manifest,
            main,
            arm,
            animations,
            textures,
            heightScale,
            widthScale,
            controllers,
            functions,
            sounds);
    }

    private static boolean blank(String s) {
        return s == null || s.trim()
            .isEmpty();
    }

    /** {@code functions/a/b@x.molang} under {@code functions} is {@code a/b@x}. */
    static String relativeName(String folder, String path) {
        String root = folder.replace('\\', '/');
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        String rel = root.isEmpty() ? path : path.substring(root.length() + 1);
        int slash = rel.lastIndexOf('/');
        int dot = rel.lastIndexOf('.');
        return dot > slash ? rel.substring(0, dot) : rel;
    }

    /** A function file, at most 256 KiB, as UTF-8. */
    private static String readText(ModelSource source, String path) throws IOException {
        try (InputStream in = source.open(path)) {
            if (in == null) {
                throw new IOException(source.name() + ": missing " + path);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > 256 * 1024) {
                    throw new IOException(source.name() + ": " + path + " is larger than 256 KiB");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static <T> T read(Gson gson, ModelSource source, String path, Class<T> type) throws IOException {
        try (InputStream in = source.open(path)) {
            if (in == null) {
                throw new IOException(source.name() + ": missing " + path);
            }
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            T value = gson.fromJson(reader, type);
            if (value == null) {
                throw new IOException(source.name() + ": empty " + path);
            }
            return value;
        } catch (JsonParseException e) {
            throw new IOException(source.name() + ": invalid " + path + ": " + e.getMessage(), e);
        }
    }
}

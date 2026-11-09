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

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.animation.AnimationFile;
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
        return new LoadedModel(source, manifest, main, arm, animations, textures, heightScale, widthScale);
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

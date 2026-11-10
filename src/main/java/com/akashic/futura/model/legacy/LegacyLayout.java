/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/LegacyManifestBuilder.java, Apache License 2.0.
 * Changes: writes the ysm.json that the 1.x layout implies (the loader then reads it like any model) instead of
 * building the manifest object; Gson 2.2.4; Java 8.
 */
package com.akashic.futura.model.legacy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeSet;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.model.ExtraInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

/**
 * The YSM 1.x layout: no {@code ysm.json}, {@code main.json} and {@code arm.json} at the top, animations as
 * {@code <set>.animation.json}, every top-level PNG a texture, {@code arrow.json}/{@code arrow.png} the arrow, and an
 * optional {@code info.json} with the name, authors, licence and extra animation names.
 */
public final class LegacyLayout {

    private static final String[] ANIMATION_SETS = { "main", "arm", "fp_arm", "extra", "tac", "carryon", "parcool",
        "swem", "slashblade", "tlm", "immersive_melodies", "irons_spell_books" };

    private LegacyLayout() {}

    /** A model in the 1.x layout: no {@code ysm.json}, {@code main.json} present. */
    public static boolean applies(Map<String, byte[]> files) {
        return !files.containsKey("ysm.json") && files.containsKey("main.json");
    }

    /** Adds the {@code ysm.json} the 1.x layout means. */
    public static void addManifest(Map<String, byte[]> files) throws IOException {
        if (!files.containsKey("arm.json")) {
            throw new IOException("1.x layout without arm.json");
        }
        boolean arrow = files.containsKey("arrow.json");
        JsonObject player = new JsonObject();
        JsonObject model = new JsonObject();
        model.addProperty("main", "main.json");
        model.addProperty("arm", "arm.json");
        player.add("model", model);
        JsonObject animation = new JsonObject();
        for (String set : ANIMATION_SETS) {
            if (files.containsKey(set + ".animation.json")) {
                animation.addProperty(set, set + ".animation.json");
            }
        }
        player.add("animation", animation);
        JsonArray textures = new JsonArray();
        JsonObject filesJson = new JsonObject();
        boolean arrowTexture = false;
        for (String path : new TreeSet<>(files.keySet())) {
            if (path.indexOf('/') >= 0 || !path.endsWith(".png")) {
                continue;
            }
            if (path.equals("arrow.png")) {
                arrowTexture = true;
                if (!arrow) {
                    throw new IOException("1.x layout: arrow.png without arrow.json");
                }
                continue;
            }
            JsonObject texture = new JsonObject();
            texture.addProperty("uv", path);
            textures.add(texture);
        }
        player.add("texture", textures);
        filesJson.add("player", player);
        if (arrow) {
            if (!arrowTexture) {
                throw new IOException("1.x layout: arrow.json without arrow.png");
            }
            JsonObject arrowJson = new JsonObject();
            JsonArray match = new JsonArray();
            match.add(new JsonPrimitive("arrow"));
            arrowJson.add("match", match);
            arrowJson.addProperty("model", "arrow.json");
            if (files.containsKey("arrow.animation.json")) {
                arrowJson.addProperty("animation", "arrow.animation.json");
            }
            arrowJson.addProperty("texture", "arrow.png");
            filesJson.add("arrow", arrowJson);
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("spec", 2);
        byte[] infoBytes = files.get("info.json");
        JsonObject properties = new JsonObject();
        if (infoBytes != null) {
            ExtraInfo info;
            try {
                info = FormatGson.create()
                    .fromJson(new String(infoBytes, StandardCharsets.UTF_8), ExtraInfo.class);
            } catch (JsonParseException e) {
                throw new IOException("1.x layout: invalid info.json: " + e.getMessage(), e);
            }
            if (info != null) {
                JsonObject metadata = new JsonObject();
                metadata.addProperty("name", info.name == null ? "" : info.name);
                metadata.addProperty("tips", info.tips == null ? "" : info.tips);
                JsonObject license = new JsonObject();
                license.addProperty("type", info.license == null ? "All Rights Reserved" : info.license);
                metadata.add("license", license);
                JsonArray authors = new JsonArray();
                if (info.authors != null) {
                    for (String author : info.authors) {
                        JsonObject a = new JsonObject();
                        a.addProperty("name", author == null ? "" : author);
                        authors.add(a);
                    }
                }
                metadata.add("authors", authors);
                manifest.add("metadata", metadata);
                JsonObject extra = new JsonObject();
                if (info.extraAnimationNames != null) {
                    for (int i = 0; i < info.extraAnimationNames.size(); i++) {
                        extra.addProperty("extra" + i, info.extraAnimationNames.get(i));
                    }
                }
                properties.add("extra_animation", extra);
                properties.addProperty("free", info.free);
            }
        }
        manifest.add("properties", properties);
        manifest.add("files", filesJson);
        files.put(
            "ysm.json",
            new Gson().toJson(manifest)
                .getBytes(StandardCharsets.UTF_8));
    }
}

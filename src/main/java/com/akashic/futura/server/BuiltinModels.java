package com.akashic.futura.server;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.manifest.ModelManifest;
import com.akashic.futura.format.pojo.manifest.models.PBRTextureSet;
import com.akashic.futura.net.Protocol;

/**
 * The built-in models, as the server sees them: the same jar runs on both sides, so the server can check a
 * {@code builtin:<folder>} key and its textures against the bundled manifests. No Minecraft classes.
 */
public final class BuiltinModels {

    private static final String BASE = "/assets/futura3d/builtin/";
    private static final List<String> MISSING = Collections.emptyList();
    private static final Map<String, List<String>> TEXTURES = new ConcurrentHashMap<>();

    private BuiltinModels() {}

    /** Whether the jar has {@code assets/futura3d/builtin/<folder>/ysm.json}. */
    public static boolean exists(String folder) {
        return textures(folder) != MISSING;
    }

    /** Texture stems of a built-in model, or an empty list if there is no such model. */
    public static List<String> textures(String folder) {
        if (!Protocol.validBuiltin(folder)) {
            return MISSING;
        }
        return TEXTURES.computeIfAbsent(folder, BuiltinModels::read);
    }

    private static List<String> read(String folder) {
        try (InputStream in = BuiltinModels.class.getResourceAsStream(BASE + folder + "/ysm.json")) {
            if (in == null) {
                return MISSING;
            }
            ModelManifest manifest = FormatGson.create()
                .fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ModelManifest.class);
            manifest.normalize();
            List<String> stems = new ArrayList<>();
            for (PBRTextureSet t : manifest.files.player.texture) {
                if (t != null && t.uv != null && !t.uv.isEmpty()) {
                    stems.add(ModelRepository.stem(t.uv.replace('\\', '/')));
                }
            }
            return Collections.unmodifiableList(stems);
        } catch (Exception e) {
            return MISSING;
        }
    }
}

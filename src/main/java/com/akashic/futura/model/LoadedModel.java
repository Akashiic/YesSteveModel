package com.akashic.futura.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.format.pojo.controller.AnimationController;
import com.akashic.futura.format.pojo.manifest.ModelManifest;
import com.akashic.futura.geo.BakedGeometry;

/** A model read and baked from its source, before any GPU work. No Minecraft classes. */
public final class LoadedModel {

    public final ModelSource source;
    public final ModelManifest manifest;
    /** Third-person geometry ({@code files.player.model.main}). */
    public final BakedGeometry main;
    /** First-person arms ({@code files.player.model.arm}). */
    public final BakedGeometry arm;
    /** Animation sets by name ({@code main}, {@code arm}, {@code extra}, ...). */
    public final Map<String, AnimationFile> animations;
    /** Texture paths inside the source, in manifest order. */
    public final List<String> textures;
    public final float heightScale;
    public final float widthScale;
    /**
     * Bedrock animation controllers of every {@code files.player.animation_controllers} file, by name
     * ({@code player.main}, {@code fp.arm.misc}...); a later file replaces a controller of the same name.
     */
    public final Map<String, AnimationController> controllers;
    /**
     * User functions: the path under {@code function_path} (default {@code functions}) without {@code .molang}, e.g.
     * {@code physics_init@player_init}, to the source, in upstream's file order.
     */
    public final Map<String, String> functions;
    /**
     * Sounds: the path under {@code sound_path} (default {@code sounds}) without its extension, e.g. {@code horn}, to
     * the file's path in the source. Read when played.
     */
    public final Map<String, String> sounds;

    LoadedModel(ModelSource source, ModelManifest manifest, BakedGeometry main, BakedGeometry arm,
        Map<String, AnimationFile> animations, List<String> textures, float heightScale, float widthScale,
        Map<String, AnimationController> controllers, Map<String, String> functions, Map<String, String> sounds) {
        this.source = source;
        this.manifest = manifest;
        this.main = main;
        this.arm = arm;
        this.animations = Collections.unmodifiableMap(animations);
        this.textures = Collections.unmodifiableList(textures);
        this.heightScale = heightScale;
        this.widthScale = widthScale;
        this.controllers = Collections.unmodifiableMap(controllers);
        this.functions = Collections.unmodifiableMap(functions);
        this.sounds = Collections.unmodifiableMap(sounds);
    }

    public String name() {
        return manifest.metadata != null && !manifest.metadata.name.isEmpty() ? manifest.metadata.name : source.name();
    }

    /** The texture drawn by default: {@code properties.default_texture} (file stem) or the first one. */
    public String defaultTexture() {
        String wanted = manifest.properties.defaultTexture;
        if (wanted != null && !wanted.isEmpty()) {
            for (String texture : textures) {
                if (stem(texture).equals(wanted)) {
                    return texture;
                }
            }
        }
        return textures.get(0);
    }

    static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}

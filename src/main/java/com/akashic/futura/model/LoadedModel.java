package com.akashic.futura.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.pojo.animation.AnimationFile;
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

    LoadedModel(ModelSource source, ModelManifest manifest, BakedGeometry main, BakedGeometry arm,
        Map<String, AnimationFile> animations, List<String> textures, float heightScale, float widthScale) {
        this.source = source;
        this.manifest = manifest;
        this.main = main;
        this.arm = arm;
        this.animations = Collections.unmodifiableMap(animations);
        this.textures = Collections.unmodifiableList(textures);
        this.heightScale = heightScale;
        this.widthScale = widthScale;
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

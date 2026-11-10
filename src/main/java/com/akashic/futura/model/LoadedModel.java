package com.akashic.futura.model;

import java.util.ArrayList;
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
    /**
     * Projectile replacements ({@code files.projectiles}, then the deprecated {@code files.arrow}), in manifest order.
     */
    public final List<ReplacementTarget> projectiles;
    /** Vehicle replacements ({@code files.vehicles}), in manifest order. */
    public final List<ReplacementTarget> vehicles;
    /** Replacement targets that could not be read (left out; the host draws those entities). */
    public final List<String> replacementErrors;

    LoadedModel(ModelSource source, ModelManifest manifest, BakedGeometry main, BakedGeometry arm,
        Map<String, AnimationFile> animations, List<String> textures, float heightScale, float widthScale,
        Map<String, AnimationController> controllers, Map<String, String> functions, Map<String, String> sounds) {
        this(
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
            sounds,
            Collections.<ReplacementTarget>emptyList(),
            Collections.<ReplacementTarget>emptyList(),
            Collections.<String>emptyList());
    }

    LoadedModel(ModelSource source, ModelManifest manifest, BakedGeometry main, BakedGeometry arm,
        Map<String, AnimationFile> animations, List<String> textures, float heightScale, float widthScale,
        Map<String, AnimationController> controllers, Map<String, String> functions, Map<String, String> sounds,
        List<ReplacementTarget> projectiles, List<ReplacementTarget> vehicles, List<String> replacementErrors) {
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
        this.projectiles = Collections.unmodifiableList(new ArrayList<>(projectiles));
        this.vehicles = Collections.unmodifiableList(new ArrayList<>(vehicles));
        this.replacementErrors = Collections.unmodifiableList(new ArrayList<>(replacementErrors));
    }

    /**
     * The projectile replacement for an entity id ({@code minecraft:arrow}): the first usable target that matches it,
     * in manifest order (upstream {@code ClientCatalogManager.findRenderTarget}), or null.
     */
    public ReplacementTarget projectile(String entityId) {
        return find(projectiles, entityId);
    }

    /** The vehicle replacement for an entity id ({@code minecraft:boat}), as {@link #projectile}, or null. */
    public ReplacementTarget vehicle(String entityId) {
        return find(vehicles, entityId);
    }

    /** Every replacement target: the projectiles, then the vehicles. */
    public List<ReplacementTarget> replacements() {
        List<ReplacementTarget> all = new ArrayList<>(projectiles);
        all.addAll(vehicles);
        return all;
    }

    private static ReplacementTarget find(List<ReplacementTarget> targets, String entityId) {
        for (ReplacementTarget target : targets) {
            if (target.usable() && target.matches(entityId)) {
                return target;
            }
        }
        return null;
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

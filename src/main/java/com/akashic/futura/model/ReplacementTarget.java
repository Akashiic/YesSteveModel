/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/RawModelAssembler.java (writeReplacedModels / writeReplacedModel: one
 * render target per files.projectiles / files.vehicles entry, the deprecated files.arrow after the projectiles),
 * src/main/java/com/elfmcys/ysm/util/ModelIdUtil.java (getEntityIdMatch: entity ids of a target's match list) and
 * src/main/java/com/elfmcys/ysm/model/catalog/content/DefaultAnimationKey.java (the default-animation domain of a
 * target: its sorted match list), Apache License 2.0.
 * Changes: a plain object of the loaded model instead of a protobuf render target; entity type tags ({@code #tag})
 * never match, since 1.7.10 has none.
 */
package com.akashic.futura.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.format.pojo.controller.AnimationController;
import com.akashic.futura.geo.BakedGeometry;

/**
 * A projectile or vehicle the model replaces ({@code files.projectiles}, {@code files.vehicles} and the deprecated
 * {@code files.arrow}): its geometry, animations, controllers and texture, and the entity ids it matches. No
 * Minecraft classes.
 */
public final class ReplacementTarget {

    /** Upstream {@code RenderTargetKind}. */
    public enum Kind {

        PROJECTILE("projectile"),
        VEHICLE("vehicle");

        /** Controller category ({@code projectile.main}) and target id prefix. */
        public final String category;

        Kind(String category) {
            this.category = category;
        }
    }

    /** What the deprecated {@code files.arrow} matches when it names nothing (the legacy layout's {@code arrow}). */
    public static final String ARROW = "minecraft:arrow";

    public final Kind kind;
    /** Position among the targets of its kind, in manifest order (upstream target id {@code projectile/0000}). */
    public final int index;
    /** The {@code match} list as written. */
    public final List<String> match;
    /** The entity ids it matches ({@code minecraft:} added where no namespace is written; tags left out). */
    public final List<String> entityIds;
    public final BakedGeometry geometry;
    /** The {@code animation} file, or null. */
    public final AnimationFile animation;
    /** Bedrock controllers of the {@code controller} file by name ({@code projectile.main}...), maybe empty. */
    public final Map<String, AnimationController> controllers;
    /** The texture's path in the source, or null: a target without texture is never used (the host draws). */
    public final String texture;

    public ReplacementTarget(Kind kind, int index, List<String> match, BakedGeometry geometry, AnimationFile animation,
        Map<String, AnimationController> controllers, String texture) {
        this.kind = kind;
        this.index = index;
        this.match = Collections.unmodifiableList(new ArrayList<>(match));
        List<String> ids = new ArrayList<>();
        for (String m : match) {
            String id = entityId(m);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        this.entityIds = Collections.unmodifiableList(ids);
        this.geometry = geometry;
        this.animation = animation;
        this.controllers = controllers == null ? Collections.<String, AnimationController>emptyMap()
            : Collections.unmodifiableMap(controllers);
        this.texture = texture;
    }

    /** Upstream's target id: {@code projectile/0000}, {@code vehicle/0002}. */
    public String id() {
        return kind.category + "/" + String.format(Locale.ROOT, "%04d", index);
    }

    /** Whether it can replace anything (upstream: a target without textures is not a usable replacement). */
    public boolean usable() {
        return texture != null;
    }

    /** Whether it matches an entity id ({@code minecraft:arrow}); a usable target is needed to replace it. */
    public boolean matches(String entityId) {
        return entityId != null && entityIds.contains(entityId);
    }

    /**
     * The default-animation domain of the target (upstream {@code DefaultAnimationKey.domain}): kind and the sorted
     * match list. A model's target borrows the default model's animations of the same domain.
     */
    public String domain() {
        List<String> sorted = new ArrayList<>(match);
        Collections.sort(sorted);
        StringBuilder b = new StringBuilder(kind.category).append('/');
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                b.append('\0');
            }
            b.append(sorted.get(i));
        }
        return b.toString();
    }

    /**
     * Upstream {@code ResourceLocation.tryParse}: {@code arrow} is {@code minecraft:arrow}; null for a tag or an
     * invalid id (upper case, spaces...).
     */
    static String entityId(String match) {
        if (match == null) {
            return null;
        }
        String m = match.trim();
        if (m.isEmpty() || m.startsWith("#")) {
            return null;
        }
        int colon = m.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : m.substring(0, colon);
        String path = colon < 0 ? m : m.substring(colon + 1);
        if (namespace.isEmpty()) {
            namespace = "minecraft";
        }
        if (path.isEmpty() || !valid(namespace, false) || !valid(path, true)) {
            return null;
        }
        return namespace + ":" + path;
    }

    private static boolean valid(String s, boolean path) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '_'
                || c == '-'
                || c == '.'
                || path && c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return id() + " " + match;
    }
}

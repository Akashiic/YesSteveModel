package com.akashic.futura.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.EntityAnimations;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.client.render.ModelTexture;
import com.akashic.futura.geo.FacePartition;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelSource;
import com.akashic.futura.model.ReplacementTarget;

/**
 * The projectile and vehicle replacements of one {@link ClientModel}, ready for the client: animations compiled and
 * texture read off the render thread ({@link #load}), display lists and uploads made on first draw, freed with the
 * model. A target whose texture cannot be read, or that fails while drawn, is never drawn again: the host draws the
 * entity (upstream: projectile and vehicle failures restore host rendering).
 */
public final class ClientReplacements {

    static final ClientReplacements NONE = new ClientReplacements(
        Collections.<Target>emptyList(),
        Collections.<Target>emptyList());

    /** One replacement target. */
    public static final class Target {

        public final ReplacementTarget target;
        public final EntityAnimations animations;
        private final ResourceLocation packLocation;
        private final ModelTexture uploaded;
        private final byte[][] partitions;
        private GeometryRenderer renderer;
        /** Unusable (texture unreadable) or failed while drawn: the host draws. */
        boolean failed;

        Target(ReplacementTarget target, EntityAnimations animations, ResourceLocation packLocation,
            ModelTexture uploaded, byte[][] partitions, boolean failed) {
            this.target = target;
            this.animations = animations;
            this.packLocation = packLocation;
            this.uploaded = uploaded;
            this.partitions = partitions;
            this.failed = failed;
        }

        /** Binds the texture (render thread: uploads it on first use). */
        void bind() {
            ResourceLocation location = packLocation != null ? packLocation : uploaded.location();
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(location);
        }

        GeometryRenderer renderer() {
            if (renderer == null) {
                renderer = new GeometryRenderer(target.geometry, partitions);
            }
            return renderer;
        }

        void release() {
            if (renderer != null) {
                renderer.delete();
                renderer = null;
            }
            if (uploaded != null) {
                uploaded.release();
            }
        }
    }

    private final List<Target> projectiles;
    private final List<Target> vehicles;

    private ClientReplacements(List<Target> projectiles, List<Target> vehicles) {
        this.projectiles = projectiles;
        this.vehicles = vehicles;
    }

    /** Compiles the targets of a model and reads their textures; no GL work. A target that fails is left unusable. */
    static ClientReplacements load(String key, LoadedModel loaded, ModelSource source,
        ClientModel.PackTextures packTextures) {
        for (String error : loaded.replacementErrors) {
            Futura3D.LOG.warn("Model {}: replacement left out: {}", key, error);
        }
        if (loaded.projectiles.isEmpty() && loaded.vehicles.isEmpty()) {
            return NONE;
        }
        return new ClientReplacements(
            targets(key, loaded, source, packTextures, loaded.projectiles),
            targets(key, loaded, source, packTextures, loaded.vehicles));
    }

    private static List<Target> targets(String key, LoadedModel loaded, ModelSource source,
        ClientModel.PackTextures packTextures, List<ReplacementTarget> targets) {
        List<Target> out = new ArrayList<>();
        for (ReplacementTarget t : targets) {
            out.add(target(key, loaded, source, packTextures, t));
        }
        return out;
    }

    private static Target target(String key, LoadedModel loaded, ModelSource source,
        ClientModel.PackTextures packTextures, ReplacementTarget t) {
        if (!t.usable()) {
            return new Target(t, null, null, null, null, true);
        }
        try {
            EntityAnimations animations = EntityAnimations.compile(loaded, t);
            BufferedImage image;
            try (InputStream in = source.open(t.texture)) {
                image = in == null ? null : ImageIO.read(in);
            }
            if (image == null) {
                Futura3D.LOG.warn("Model {}: texture {} of {} is missing or unreadable", key, t.texture, t);
                return new Target(t, animations, null, null, null, true);
            }
            byte[][] partitions = FacePartition
                .classify(t.geometry, FacePartition.Alpha.of(image), loaded.manifest.properties.forceCulling);
            ResourceLocation pack = packTextures == null ? null : packTextures.location(t.texture);
            ModelTexture uploaded = pack == null ? new ModelTexture(image) : null;
            return new Target(t, animations, pack, uploaded, partitions, false);
        } catch (IOException | RuntimeException e) {
            Futura3D.LOG.warn("Model {}: could not prepare {}", key, t, e);
            return new Target(t, null, null, null, null, true);
        }
    }

    /**
     * The projectile replacement for an entity id: the first usable target of the model that matches it (upstream
     * {@code findRenderTarget}), or null when there is none or that target cannot be drawn (the host draws).
     */
    public Target projectile(String entityId) {
        return find(projectiles, entityId);
    }

    /** The vehicle replacement for an entity id, as {@link #projectile}. */
    public Target vehicle(String entityId) {
        return find(vehicles, entityId);
    }

    private static Target find(List<Target> targets, String entityId) {
        for (Target t : targets) {
            if (t.target.usable() && t.target.matches(entityId)) {
                return t.failed ? null : t;
            }
        }
        return null;
    }

    /** Frees the display lists and uploaded textures (render thread). */
    void release() {
        for (Target t : projectiles) {
            t.release();
        }
        for (Target t : vehicles) {
            t.release();
        }
    }
}

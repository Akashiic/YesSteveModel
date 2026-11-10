package com.akashic.futura.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.ModelAnimations;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.client.render.ModelTexture;
import com.akashic.futura.geo.FacePartition;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelInfo;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ModelSource;

/**
 * One model ready for the client: read, baked and compiled off the render thread ({@link #load}), and its GL resources
 * (display lists, uploaded textures) made on the render thread on first draw and freed by {@link #release}.
 *
 * <p>
 * Each texture has its own face partitions (opaque and translucent faces come from that texture's alpha); textures
 * with the same partitions share one set of display lists.
 */
public final class ClientModel {

    public final String key;
    public final LoadedModel loaded;
    public final ModelInfo info;
    public final ModelAnimations animations;
    /** The first-person arms, or null if they could not be compiled. */
    final ModelAnimations arms;
    /** Projectile and vehicle replacements. */
    public final ClientReplacements replacements;

    private final Skin[] skins;
    private final byte[][][] partitions;
    private final GeometryRenderer[] renderers;
    private final int defaultSkin;
    private GeometryRenderer armRenderer;
    private boolean released;
    /** Reported once per model: a broken animation draws the rest pose. */
    boolean animationFailed;
    boolean armAnimationFailed;

    /** One texture of the model. */
    private static final class Skin {

        final String stem;
        /** A texture in the resource packs (built-in models), or null. */
        final ResourceLocation packLocation;
        /** A texture read from the model's source, or null. */
        final ModelTexture uploaded;
        /** Index into {@link ClientModel#partitions}. */
        final int partition;

        Skin(String stem, ResourceLocation packLocation, ModelTexture uploaded, int partition) {
            this.stem = stem;
            this.packLocation = packLocation;
            this.uploaded = uploaded;
            this.partition = partition;
        }

        ResourceLocation location() {
            return packLocation != null ? packLocation : uploaded != null ? uploaded.location() : MISSING;
        }
    }

    private static final ResourceLocation MISSING = new ResourceLocation(Futura3D.MODID, "missing_model_texture");

    private ClientModel(String key, LoadedModel loaded, ModelInfo info, ModelAnimations animations,
        ModelAnimations arms, Skin[] skins, byte[][][] partitions, int defaultSkin, ClientReplacements replacements) {
        this.key = key;
        this.loaded = loaded;
        this.info = info;
        this.animations = animations;
        this.arms = arms;
        this.skins = skins;
        this.partitions = partitions;
        this.renderers = new GeometryRenderer[partitions.length];
        this.defaultSkin = defaultSkin;
        this.replacements = replacements;
    }

    /**
     * Reads, bakes and compiles a model; no GL work, safe off the render thread.
     *
     * @param packTextures where a texture path of this model is in the resource packs (built-in models), or null to
     *                     read the textures from the source and upload them
     */
    static ClientModel load(String key, ModelSource source, PackTextures packTextures) throws IOException {
        LoadedModel loaded = ModelLoader.load(source);
        ModelInfo info = ModelInfo.of(
            loaded.manifest,
            key,
            com.akashic.futura.model.ModelLang.readQuietly(source, loaded.manifest.files.languagePath));
        ModelAnimations animations = ModelAnimations.compile(loaded);
        ModelAnimations arms = null;
        try {
            arms = ModelAnimations.compileFirstPerson(loaded);
        } catch (RuntimeException e) {
            Futura3D.LOG.error("Could not prepare the first-person arms of model {}", key, e);
        }
        List<String> paths = loaded.textures;
        Skin[] skins = new Skin[paths.size()];
        byte[][][] partitions = new byte[paths.size()][][];
        int distinct = 0;
        String defaultPath = loaded.defaultTexture();
        int defaultSkin = 0;
        for (int i = 0; i < skins.length; i++) {
            String path = paths.get(i);
            BufferedImage image = null;
            try (InputStream in = source.open(path)) {
                image = in == null ? null : ImageIO.read(in);
            } catch (IOException | RuntimeException e) {
                Futura3D.LOG.warn("Could not read texture {} of model {}", path, key, e);
            }
            if (image == null) {
                Futura3D.LOG.warn("Model {}: texture {} is missing or unreadable", key, path);
            }
            // The same image gives the face partitions and, for models outside the resource packs, the texture.
            FacePartition.Alpha alpha = image == null ? null : FacePartition.Alpha.of(image);
            byte[][] parts = FacePartition.classify(loaded.main, alpha, loaded.manifest.properties.forceCulling);
            int index = -1;
            for (int j = 0; j < distinct; j++) {
                if (Arrays.deepEquals(partitions[j], parts)) {
                    index = j;
                    break;
                }
            }
            if (index < 0) {
                index = distinct;
                partitions[distinct++] = parts;
            }
            ResourceLocation pack = packTextures == null ? null : packTextures.location(path);
            ModelTexture uploaded = pack == null && image != null ? new ModelTexture(image) : null;
            skins[i] = new Skin(stemOf(path), pack, uploaded, index);
            if (path.equals(defaultPath)) {
                defaultSkin = i;
            }
        }
        return new ClientModel(
            key,
            loaded,
            info,
            animations,
            arms,
            skins,
            Arrays.copyOf(partitions, distinct),
            defaultSkin,
            ClientReplacements.load(key, loaded, source, packTextures));
    }

    /** Where a built-in model's texture is in the resource packs. */
    interface PackTextures {

        ResourceLocation location(String path);
    }

    private static String stemOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** The texture index of a texture name (file stem), or the default texture's if null or unknown. */
    public int skin(String stem) {
        if (stem != null) {
            for (int i = 0; i < skins.length; i++) {
                if (skins[i].stem.equals(stem)) {
                    return i;
                }
            }
        }
        return defaultSkin;
    }

    /** Where a texture is bound from (render thread: uploads it on first use). */
    public ResourceLocation texture(int skin) {
        return skins[clamp(skin)].location();
    }

    /** Binds a texture (render thread). */
    public void bind(int skin) {
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture(skin));
    }

    /** The third-person renderer for a texture (its face partitions); display lists are made on its first draw. */
    public GeometryRenderer renderer(int skin) {
        int p = skins[clamp(skin)].partition;
        if (renderers[p] == null) {
            renderers[p] = new GeometryRenderer(loaded.main, partitions[p]);
        }
        return renderers[p];
    }

    /** The first-person arm renderer. */
    GeometryRenderer armRenderer() {
        if (armRenderer == null) {
            armRenderer = new GeometryRenderer(loaded.arm);
        }
        return armRenderer;
    }

    private int clamp(int skin) {
        return skin < 0 || skin >= skins.length ? defaultSkin : skin;
    }

    public boolean released() {
        return released;
    }

    /** Frees the display lists and uploaded textures (render thread). The model is not drawn afterwards. */
    void release() {
        released = true;
        for (int i = 0; i < renderers.length; i++) {
            if (renderers[i] != null) {
                renderers[i].delete();
                renderers[i] = null;
            }
        }
        if (armRenderer != null) {
            armRenderer.delete();
            armRenderer = null;
        }
        for (Skin skin : skins) {
            if (skin.uploaded != null) {
                skin.uploaded.release();
            }
        }
        replacements.release();
    }

    /** Distinct face partitions (one display list set each), for logs. */
    int partitionSets() {
        return partitions.length;
    }
}

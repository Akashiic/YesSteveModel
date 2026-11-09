package com.akashic.futura.client.render;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import com.akashic.futura.Futura3D;

/**
 * A model texture that is not in the resource packs (a model sent by a server, a folder or a zip): decoded off the
 * render thread, uploaded on first use, registered with the {@code TextureManager} under a generated
 * {@code futura3d:dynamic/model_<n>} location, and freed with the model.
 *
 * <p>
 * Like vanilla's {@code DynamicTexture}, but the pixels are dropped once uploaded, and a freed location is reused by
 * the next texture: the {@code TextureManager} never forgets a location, so this keeps its map as small as the most
 * textures alive at once. A resource reload keeps the uploaded texture.
 */
public final class ModelTexture extends AbstractTexture {

    private static final Deque<ResourceLocation> FREE = new ArrayDeque<>();
    private static int counter;

    private BufferedImage image;
    private ResourceLocation location;

    /** @param image the decoded texture; kept until {@link #location()} uploads it */
    public ModelTexture(BufferedImage image) {
        this.image = image;
    }

    /** Uploads the texture on first call (render thread) and returns where it is bound from. */
    public ResourceLocation location() {
        if (location == null) {
            ResourceLocation free = FREE.poll();
            location = free != null ? free : new ResourceLocation(Futura3D.MODID, "dynamic/model_" + ++counter);
            if (image != null) {
                TextureUtil.uploadTextureImageAllocate(getGlTextureId(), image, false, false);
                image = null;
            }
            Minecraft.getMinecraft()
                .getTextureManager()
                .loadTexture(location, this);
        }
        return location;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) {
        // Uploaded by location(); nothing to read from the resource packs.
    }

    /** Frees the GL texture and gives the location back (render thread). */
    public void release() {
        image = null;
        deleteGlTexture();
        if (location != null) {
            FREE.push(location);
            location = null;
        }
    }
}

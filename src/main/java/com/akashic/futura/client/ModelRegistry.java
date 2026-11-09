package com.akashic.futura.client;

import java.util.List;

import com.akashic.futura.model.ModelSource;

/**
 * The models the client can draw: the built-in ones ({@code builtin:<folder>}, e.g. {@code builtin:default},
 * {@code builtin:wine_fox/18_wedding}) and the ones a server sent ({@code server:<model id>}).
 * <p>
 * Phase 4 contract between the network layer (which registers server models) and the client renderer and picker GUI
 * (which implement this class). Render thread only unless stated otherwise.
 */
public final class ModelRegistry {

    public static final ModelRegistry INSTANCE = new ModelRegistry();

    /** Key prefix of built-in models. */
    public static final String BUILTIN = "builtin:";
    /** Key prefix of models received from the server. */
    public static final String SERVER = "server:";

    private ModelRegistry() {}

    /**
     * Registers a model sent by the server. Loading, baking and animation compilation happen off the render thread;
     * the model becomes drawable later (until then, players who selected it are drawn with the default model).
     * Registering a key again replaces the model.
     *
     * @param key    {@code server:<model id>}
     * @param source the model's files (already verified against their hashes)
     * @param locked true when the local player may see but not select it (restricted model without permission)
     */
    public void registerServerModel(String key, ModelSource source, boolean locked) {
        throw new UnsupportedOperationException("phase 4b");
    }

    /** Forgets every server model (on disconnect); players using them fall back to the default model. */
    public void clearServerModels() {
        throw new UnsupportedOperationException("phase 4b");
    }

    /** Every known key, built-in first, in display order. */
    public List<String> keys() {
        throw new UnsupportedOperationException("phase 4b");
    }

    /** Whether the model is loaded and drawable. */
    public boolean isReady(String key) {
        throw new UnsupportedOperationException("phase 4b");
    }
}

package com.akashic.futura.client;

import java.util.UUID;

/**
 * Which model each player is drawn with. Phase 4 contract between the network layer and the client renderer.
 * Render thread only.
 */
public final class ModelSelection {

    public static final ModelSelection INSTANCE = new ModelSelection();

    /** What the local player chose in the picker: applied locally, or sent to the server when one handles it. */
    public interface Handler {

        /**
         * @param key     a {@link ModelRegistry} key
         * @param texture the texture stem, or null for the model's default texture
         */
        void requested(String key, String texture);
    }

    private ModelSelection() {}

    /**
     * Sets a player's model (from the server, or locally without a server).
     *
     * @param key     a {@link ModelRegistry} key, or null for the default model
     * @param texture texture stem, or null for the model's default texture
     */
    public void select(UUID player, String key, String texture) {
        throw new UnsupportedOperationException("phase 4b");
    }

    /** The player's model key, or null when they use the default model. */
    public String keyOf(UUID player) {
        throw new UnsupportedOperationException("phase 4b");
    }

    /** Forgets every player's selection (on disconnect), except the local player's local choice. */
    public void clearRemote() {
        throw new UnsupportedOperationException("phase 4b");
    }

    /**
     * Who handles the local player's choices: null (the default) applies them locally with {@link #select}; the
     * network layer installs a handler while connected to a server that runs the mod, and removes it on disconnect.
     */
    public void setHandler(Handler handler) {
        throw new UnsupportedOperationException("phase 4b");
    }

    /** Called by the picker GUI when the local player chooses a model. */
    public void requestLocal(String key, String texture) {
        throw new UnsupportedOperationException("phase 4b");
    }
}

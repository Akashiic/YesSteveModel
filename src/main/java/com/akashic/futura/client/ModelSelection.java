package com.akashic.futura.client;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import com.akashic.futura.Futura3D;

/**
 * Which model each player is drawn with. Phase 4 contract between the network layer and the client renderer.
 * Render thread only.
 *
 * <p>
 * A player is drawn with the model {@link #select} gave him. The local player, while no {@link Handler} is installed
 * (single player, or a server without the mod), is drawn with his own choice from the picker, which is kept in
 * {@code config/futura3d-client.properties} between game sessions; a selection the server sent for him wins over it
 * when there is one.
 */
public final class ModelSelection {

    public static final ModelSelection INSTANCE = new ModelSelection();

    /** File of the local choice, under the game directory. */
    public static final String CONFIG_FILE = "config/futura3d-client.properties";

    /** What the local player chose in the picker: applied locally, or sent to the server when one handles it. */
    public interface Handler {

        /**
         * @param key     a {@link ModelRegistry} key
         * @param texture the texture stem, or null for the model's default texture
         */
        void requested(String key, String texture);
    }

    private static final class Choice {

        final String key;
        final String texture;

        Choice(String key, String texture) {
            this.key = key;
            this.texture = texture;
        }
    }

    private final Map<UUID, Choice> selections = new HashMap<>();
    private Handler handler;
    private Choice local;
    private boolean localLoaded;
    private LocalChoiceStore store;

    private ModelSelection() {}

    /**
     * Sets a player's model (from the server, or locally without a server).
     *
     * @param key     a {@link ModelRegistry} key, or null for the default model
     * @param texture texture stem, or null for the model's default texture
     */
    public void select(UUID player, String key, String texture) {
        if (player == null) {
            return;
        }
        if (key == null && texture == null) {
            selections.remove(player);
        } else {
            selections.put(player, new Choice(key, texture));
        }
    }

    /** The player's model key, or null when they use the default model. */
    public String keyOf(UUID player) {
        Choice choice = choiceOf(player);
        return choice == null ? null : choice.key;
    }

    /** The player's texture stem, or null for the model's default texture. */
    public String textureOf(UUID player) {
        Choice choice = choiceOf(player);
        return choice == null ? null : choice.texture;
    }

    private Choice choiceOf(UUID player) {
        if (player == null) {
            return null;
        }
        Choice choice = selections.get(player);
        if (choice == null && handler == null && isLocalPlayer(player) && !onRemoteServer()) {
            choice = local();
        }
        return choice;
    }

    /**
     * In a multiplayer world. Without a handler there, the server does not run the mod (a lobby): everyone, the local
     * player too, has the default look (his own skin on Steve/Alex), and the saved local choice waits for a server
     * with the mod. Only the main menu and singleplayer before its handshake use the local choice directly.
     */
    public static boolean onRemoteServer() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld != null && !mc.isSingleplayer();
    }

    /** Forgets every player's selection (on disconnect), except the local player's local choice. */
    public void clearRemote() {
        selections.clear();
    }

    /**
     * Who handles the local player's choices: null (the default) applies them locally with {@link #select}; the
     * network layer installs a handler while connected to a server that runs the mod, and removes it on disconnect.
     */
    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    /** Called by the picker GUI when the local player chooses a model. */
    public void requestLocal(String key, String texture) {
        Handler h = handler;
        if (h != null) {
            h.requested(key, texture);
            return;
        }
        local = key == null && texture == null ? null : new Choice(key, texture);
        localLoaded = true;
        Minecraft mc = Minecraft.getMinecraft();
        if (onRemoteServer() && mc.thePlayer != null) {
            mc.thePlayer
                .addChatMessage(new net.minecraft.util.ChatComponentTranslation("gui.futura3d.picker.no_server"));
        }
        UUID me = localPlayer.get();
        if (me != null) {
            // The local choice is what the local player is drawn with now, whatever was selected for him before.
            selections.remove(me);
        }
        try {
            store().save(key, texture);
        } catch (IOException e) {
            Futura3D.LOG.warn("Could not save the model choice to {}", store().file(), e);
        }
    }

    /** Whether a handler (the network layer) receives the local player's choices. */
    public boolean hasHandler() {
        return handler != null;
    }

    /** The saved local choice's key (for the network layer to send on joining), or null. */
    public String localKey() {
        Choice choice = local();
        return choice == null ? null : choice.key;
    }

    /** The saved local choice's texture, or null. */
    public String localTexture() {
        Choice choice = local();
        return choice == null ? null : choice.texture;
    }

    private Choice local() {
        if (!localLoaded) {
            localLoaded = true;
            String[] saved = store().load();
            local = saved == null ? null : new Choice(saved[0], saved[1]);
        }
        return local;
    }

    private LocalChoiceStore store() {
        if (store == null) {
            store = new LocalChoiceStore(new File(Minecraft.getMinecraft().mcDataDir, CONFIG_FILE));
        }
        return store;
    }

    /** The local player's UUID, or null without a world (replaced by tests, which run without the game). */
    java.util.function.Supplier<UUID> localPlayer = () -> {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();
    };

    private boolean isLocalPlayer(UUID player) {
        return player.equals(localPlayer.get());
    }
}

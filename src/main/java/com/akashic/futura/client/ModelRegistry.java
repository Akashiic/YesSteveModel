package com.akashic.futura.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.util.ResourceLocation;

import com.akashic.futura.Futura3D;
import com.akashic.futura.model.BuiltinIndex;
import com.akashic.futura.model.ModelInfo;
import com.akashic.futura.model.ModelSource;

/**
 * The models the client can draw: the built-in ones ({@code builtin:<folder>}, e.g. {@code builtin:default},
 * {@code builtin:wine_fox/18_wedding}) and the ones a server sent ({@code server:<model id>}).
 * <p>
 * Phase 4 contract between the network layer (which registers server models) and the client renderer and picker GUI
 * (which implement this class). Render thread only unless stated otherwise.
 *
 * <p>
 * Built-in models are listed at start-up from their manifests alone and loaded on first use (a player selects one,
 * the picker shows it), except {@code builtin:default}, the fallback, loaded at once. Loading, baking and animation
 * compilation run on a small executor; GL work (display lists, texture upload) happens on the render thread on first
 * draw. A loaded built-in model stays loaded; server models are freed by {@link #clearServerModels}.
 */
public final class ModelRegistry {

    public static final ModelRegistry INSTANCE = new ModelRegistry();

    /** Key prefix of built-in models. */
    public static final String BUILTIN = "builtin:";
    /** Key prefix of models received from the server. */
    public static final String SERVER = "server:";
    /** The fallback model: drawn for players without a model, or whose model is not ready yet. */
    public static final String DEFAULT = BUILTIN + "default";

    /** Loading state of a model. */
    public enum State {
        /** Listed, not loaded (built-in models until first use). */
        NOT_LOADED,
        LOADING,
        READY,
        FAILED
    }

    private static final class Entry {

        final String key;
        final ModelSource source;
        /** Built-in folder, or null for a server model. */
        final String folder;
        final boolean locked;
        /** Set when the entry is replaced or dropped: a load still running does not publish. */
        volatile boolean cancelled;
        volatile ModelInfo info;
        volatile State state = State.NOT_LOADED;
        /** Loaded off the render thread, picked up by the render thread. */
        volatile ClientModel pending;
        /** The drawable model (render thread only). */
        ClientModel current;
        Future<?> task;

        Entry(String key, ModelSource source, String folder, boolean locked) {
            this.key = key;
            this.source = source;
            this.folder = folder;
            this.locked = locked;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final com.akashic.futura.net.ModelAccess access = new com.akashic.futura.net.ModelAccess();
    private ExecutorService executor;
    private boolean initialised;

    private ModelRegistry() {}

    /** Lists the built-in models and loads the default one (render thread, at start-up). */
    public synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        List<String> folders;
        try {
            folders = BuiltinIndex.read();
        } catch (IOException e) {
            Futura3D.LOG.error("Could not read the built-in model index", e);
            folders = new ArrayList<>();
        }
        if (!folders.contains("default")) {
            folders.add(0, "default");
        }
        for (String folder : folders) {
            Entry entry = new Entry(BUILTIN + folder, BuiltinIndex.source(folder), folder, false);
            try {
                entry.info = ModelInfo.read(entry.source);
            } catch (IOException | RuntimeException e) {
                Futura3D.LOG.error("Built-in model {} is unreadable", folder, e);
                entry.state = State.FAILED;
            }
            entries.put(entry.key, entry);
        }
        loadNow(DEFAULT);
        // the default look (a player's own skin on Steve or Alex, PlayerSkins) needs both models at once
        for (String key : new String[] { PlayerSkins.STEVE, PlayerSkins.ALEX }) {
            if (entries.containsKey(key)) {
                loadNow(key);
            }
        }
    }

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
        init();
        if (key == null || !key.startsWith(SERVER) || source == null) {
            throw new IllegalArgumentException("Not a server model: " + key);
        }
        Entry old = entries.get(key);
        Entry entry = new Entry(key, source, null, locked);
        if (old != null) {
            cancel(old);
            // Drawn until the new one is ready.
            entry.current = old.current;
            entry.pending = old.pending;
            entry.info = old.info;
        }
        entries.put(key, entry);
        start(entry);
    }

    /** Forgets every server model (on disconnect); players using them fall back to the default model. */
    public void clearServerModels() {
        boolean any = false;
        for (Iterator<Entry> it = entries.values()
            .iterator(); it.hasNext();) {
            Entry entry = it.next();
            if (entry.folder == null) {
                cancel(entry);
                release(entry);
                it.remove();
                any = true;
            }
        }
        if (any) {
            PlayerModels.INSTANCE.forgetReleased();
        }
    }

    /**
     * Frees the built-in models that no player is drawn with (the picker loads every model it shows), except the
     * default one; they load again when needed.
     *
     * @param keep the keys players have selected (kept even while they load)
     * @param used the models players are drawn with now
     */
    public void releaseUnused(java.util.Set<String> keep, java.util.Set<ClientModel> used) {
        boolean any = false;
        for (Entry entry : entries.values()) {
            if (entry.folder == null || DEFAULT.equals(entry.key)
                || PlayerSkins.STEVE.equals(entry.key)
                || PlayerSkins.ALEX.equals(entry.key)
                || keep.contains(entry.key)) {
                continue;
            }
            ClientModel current = entry.current;
            if (current != null && !used.contains(current)) {
                entry.current = null;
                current.release();
                entry.state = State.NOT_LOADED;
                any = true;
            }
            ClientModel pending = entry.pending;
            if (pending != null && !used.contains(pending) && entry.current == null) {
                entry.pending = null;
                entry.state = State.NOT_LOADED;
            }
            if (entry.state == State.LOADING && entry.task != null && entry.task.cancel(false)) {
                entry.state = State.NOT_LOADED;
            }
        }
        if (any) {
            PlayerModels.INSTANCE.forgetReleased();
        }
    }

    /** Every known key, built-in first, in display order. */
    public List<String> keys() {
        init();
        return new ArrayList<>(entries.keySet());
    }

    /** Whether the model is loaded and drawable. */
    public boolean isReady(String key) {
        Entry entry = key == null ? null : entries.get(key);
        return entry != null && (entry.current != null || entry.pending != null);
    }

    // ---- For the renderer and the picker ----

    /** Whether the key is known (listed built-in or registered server model). */
    public boolean contains(String key) {
        return key != null && entries.containsKey(key);
    }

    /** The manifest information of a model, or null if unknown or not read yet (a server model still loading). */
    public ModelInfo info(String key) {
        Entry entry = key == null ? null : entries.get(key);
        return entry == null ? null : entry.info;
    }

    /**
     * Whether the local player may see but not select the model: a server model the catalog marks locked, or any
     * model (built-in ones too) the server's access list locks (permission nodes, protocol 4).
     */
    public boolean isLocked(String key) {
        Entry entry = key == null ? null : entries.get(key);
        return entry != null && (entry.locked || access.locked(key));
    }

    /** The server's access list (protocol 4), render thread. */
    public void access(boolean reset, boolean listsUnlocked, List<String> keys) {
        access.apply(reset, listsUnlocked, keys);
    }

    /** Forgets the server's access list (disconnected): nothing is locked without a server. */
    public void clearAccess() {
        access.clear();
    }

    public State state(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) {
            return State.FAILED;
        }
        return entry.current != null || entry.pending != null ? State.READY : entry.state;
    }

    /**
     * The drawable model, or null if it is not ready; a built-in model that was never loaded starts loading. A model
     * that finished loading in the background replaces the previous one here.
     */
    public ClientModel get(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) {
            return null;
        }
        ClientModel pending = entry.pending;
        if (pending != null) {
            entry.pending = null;
            if (entry.current != null && entry.current != pending) {
                entry.current.release();
                PlayerModels.INSTANCE.forgetReleased();
            }
            entry.current = pending;
        }
        if (entry.current == null && entry.state == State.NOT_LOADED) {
            start(entry);
        }
        return entry.current;
    }

    /** Starts loading a model in the background if it is not loaded or loading. */
    public void request(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry != null && entry.current == null && entry.pending == null && entry.state == State.NOT_LOADED) {
            start(entry);
        }
    }

    /** Loads a model on this thread if it is not ready (start-up, development harness). */
    public ClientModel loadNow(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) {
            return null;
        }
        ClientModel ready = get(key);
        if (ready != null) {
            return ready;
        }
        if (entry.task != null) {
            entry.task.cancel(false);
        }
        load(entry);
        return get(key);
    }

    private void start(Entry entry) {
        entry.state = State.LOADING;
        entry.task = executor().submit(() -> load(entry));
    }

    private void load(Entry entry) {
        if (entry.cancelled) {
            return;
        }
        try {
            if (entry.info == null) {
                entry.info = ModelInfo.read(entry.source);
            }
            long start = System.nanoTime();
            String folder = entry.folder;
            ClientModel model = ClientModel.load(
                entry.key,
                entry.source,
                folder == null ? null : path -> new ResourceLocation(Futura3D.MODID, "builtin/" + folder + "/" + path));
            entry.info = model.info;
            if (entry.cancelled) {
                return;
            }
            entry.pending = model;
            entry.state = State.READY;
            Futura3D.LOG.info(
                "Model {} ({}): {} bones, {} cubes, {} quads, {} textures, loaded in {} ms",
                entry.key,
                model.info.name,
                model.loaded.main.bones.length,
                model.loaded.main.cubeCount,
                model.loaded.main.quadCount(),
                model.loaded.textures.size(),
                (System.nanoTime() - start) / 1000000);
        } catch (Exception | LinkageError e) {
            entry.state = State.FAILED;
            Futura3D.LOG.error("Could not load model {}", entry.key, e);
        }
    }

    private static void cancel(Entry entry) {
        entry.cancelled = true;
        if (entry.task != null) {
            entry.task.cancel(false);
        }
    }

    private static void release(Entry entry) {
        ClientModel pending = entry.pending;
        entry.pending = null;
        if (entry.current != null) {
            entry.current.release();
            entry.current = null;
        }
        if (pending != null) {
            pending.release();
        }
    }

    private synchronized ExecutorService executor() {
        if (executor == null) {
            AtomicInteger n = new AtomicInteger();
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "futura3d-model-loader-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }
        return executor;
    }
}

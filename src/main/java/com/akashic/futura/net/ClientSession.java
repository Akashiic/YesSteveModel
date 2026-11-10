package com.akashic.futura.net;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The client's side of one connection, without Minecraft: says hello, collects the catalog, asks the server for the
 * files missing from the cache (a few at a time), reassembles and verifies them, and announces each model once all
 * its files are stored. Player states and emotes are passed on. Not thread-safe: one thread (the client's) drives it.
 */
public final class ClientSession {

    /** Files requested and not yet complete. */
    public static final int MAX_INFLIGHT_FILES = 16;
    /** Bytes requested and not yet complete (a single bigger file is still requested alone). */
    public static final long MAX_INFLIGHT_BYTES = 8L * 1024 * 1024;

    /** Sends a message to the server. */
    public interface Output {

        void send(Message message);
    }

    /** What the session found out. Every call comes from the thread that calls {@link #handle}. */
    public interface Listener {

        /** The server runs the mod with the same protocol (first hello of the connection). */
        void connected();

        /** The server runs another protocol version: nothing else will be handled. */
        void incompatible(int serverVersion);

        /** A new catalog replaces the previous one: forget the server models registered so far. */
        void catalogReset(List<CatalogEntry> catalog);

        /** All files of a model are in the cache and verified. */
        void modelReady(CatalogEntry entry);

        /** A model cannot be completed in this connection. */
        void modelFailed(CatalogEntry entry, String reason);

        void state(UUID player, String key, String texture);

        void emote(UUID player, String name);

        /** Version 2: a player's roaming variables ({@code reset}: forget his others first). */
        default void roaming(UUID player, boolean reset, java.util.Map<String, Float> values) {}

        /** Version 2: a player's model ran {@code ysm.sync(args)} (the local player's own come back too). */
        default void sync(UUID player, float[] args) {}

        /** Version 3: the player who threw the projectile with this entity id. */
        default void projectileOwner(int entityId, UUID owner) {}

        /** Version 4: which models the local player may select (see {@link ModelAccess#apply}). */
        default void access(boolean reset, boolean listsUnlocked, List<String> keys) {}
    }

    private enum Phase {
        WAITING,
        CONNECTED,
        INCOMPATIBLE
    }

    private static final class Assembly {

        final byte[] data;
        int received;

        Assembly(int size) {
            data = new byte[size];
        }
    }

    private final FileCache cache;
    private final Output out;
    private final Listener listener;
    private Phase phase = Phase.WAITING;
    /** The version of the last hello sent; lowered once if the server only speaks an older one. */
    private int version = Protocol.VERSION;
    private UUID selfOnServer;
    private UUID localPlayer;

    // Catalog being received
    private int pendingRevision;
    private int pendingCount = -1;
    private final List<CatalogEntry> pending = new ArrayList<>();

    // Current catalog
    private final Map<Sha256, CatalogEntry> catalog = new LinkedHashMap<>();
    private final Set<Sha256> waitingModels = new HashSet<>();
    private final Map<Sha256, Integer> sizes = new HashMap<>();
    private final Set<Sha256> stored = new HashSet<>();
    /** Files of memory-only models (and of no other model of the catalog): never written to {@link #cache}. */
    private final VolatileFileCache memory = new VolatileFileCache();
    private final Set<Sha256> memoryOnly = new HashSet<>();
    private final Set<Sha256> failed = new HashSet<>();
    private final Deque<Sha256> needed = new ArrayDeque<>();
    private final Map<Sha256, Assembly> inflight = new LinkedHashMap<>();
    private long inflightBytes;
    private long downloaded;

    public ClientSession(FileCache cache, Output out, Listener listener) {
        this.cache = cache;
        this.out = out;
        this.listener = listener;
    }

    /**
     * Where the files of the catalog's models are read from: memory first (memory-only models), then the disk cache.
     * Hand this to {@link CachedModelSource}.
     */
    public FileCache files() {
        return new FileCache() {

            @Override
            public boolean has(Sha256 hash, int size) {
                return memory.has(hash, size) || cache.has(hash, size);
            }

            @Override
            public byte[] read(Sha256 hash) throws IOException {
                byte[] data = memory.read(hash);
                return data != null ? data : cache.read(hash);
            }

            @Override
            public void store(Sha256 hash, byte[] data) {
                throw new UnsupportedOperationException("the session stores files");
            }
        };
    }

    /** Files held in memory for memory-only models (for tests and the harness). */
    public int memoryFiles() {
        return memory.size();
    }

    /** Says hello. On a server without the mod nothing answers, and the session stays waiting. */
    public void start() {
        out.send(new Message.ClientHello(version));
    }

    /**
     * Says hello again if nothing answered yet (behind a proxy the first hello can be lost in a server switch: sent to
     * the previous server, or before the new one knows the player). No effect once connected or incompatible.
     *
     * @return whether a hello was sent
     */
    public boolean helloAgain() {
        if (phase != Phase.WAITING) {
            return false;
        }
        out.send(new Message.ClientHello(version));
        return true;
    }

    /** The protocol version spoken with this server (meaningful once {@link #connected}). */
    public int version() {
        return version;
    }

    /** Whether the server takes roaming variables and {@code ysm.sync} (connected with version 2 or later). */
    public boolean hasRoaming() {
        return phase == Phase.CONNECTED && Protocol.hasRoaming(version);
    }

    /**
     * The local player's UUID in this client. States and emotes the server sends about the player's UUID on the
     * server (the hello says which) are passed on with this one: they differ on offline-mode servers.
     */
    public void setLocalPlayer(UUID local) {
        this.localPlayer = local;
    }

    private UUID map(UUID player) {
        return localPlayer != null && player.equals(selfOnServer) ? localPlayer : player;
    }

    public boolean connected() {
        return phase == Phase.CONNECTED;
    }

    /** Models of the current catalog (empty before the first complete catalog). */
    public List<CatalogEntry> catalog() {
        return new ArrayList<>(catalog.values());
    }

    /** Files still to download or downloading. */
    public int pendingFiles() {
        return needed.size() + inflight.size();
    }

    /** Bytes received from the server in this connection. */
    public long downloadedBytes() {
        return downloaded;
    }

    public void handle(Message m) {
        if (phase == Phase.INCOMPATIBLE) {
            return;
        }
        if (m instanceof Message.ServerHello) {
            hello((Message.ServerHello) m);
            return;
        }
        if (phase != Phase.CONNECTED) {
            return; // nothing before the hello
        }
        switch (m.type()) {
            case Protocol.S_MODEL: {
                Message.ModelEntry e = (Message.ModelEntry) m;
                if (pendingCount >= 0 && e.revision == pendingRevision && pending.size() < pendingCount) {
                    pending.add(e.entry);
                }
                break;
            }
            case Protocol.S_CATALOG_END: {
                Message.CatalogEnd end = (Message.CatalogEnd) m;
                if (pendingCount >= 0 && end.revision == pendingRevision) {
                    commit();
                }
                break;
            }
            case Protocol.S_CHUNK:
                chunk((Message.FileChunk) m);
                break;
            case Protocol.S_UNAVAILABLE:
                fail(((Message.FileUnavailable) m).hash, "the server cannot send a file");
                break;
            case Protocol.S_STATE:
                for (Message.PlayerState s : ((Message.PlayerStates) m).states) {
                    listener.state(map(s.player), s.key, s.texture);
                }
                break;
            case Protocol.S_EMOTE: {
                Message.Emote e = (Message.Emote) m;
                listener.emote(map(e.player), e.name);
                break;
            }
            case Protocol.S_ROAMING:
                if (Protocol.hasRoaming(version)) {
                    Message.Roaming r = (Message.Roaming) m;
                    listener.roaming(map(r.player), r.reset, r.values);
                }
                break;
            case Protocol.S_SYNC:
                if (Protocol.hasRoaming(version)) {
                    Message.Sync sync = (Message.Sync) m;
                    listener.sync(map(sync.player), sync.args);
                }
                break;
            case Protocol.S_PROJECTILE_OWNER:
                if (Protocol.hasProjectileOwners(version)) {
                    Message.ProjectileOwner o = (Message.ProjectileOwner) m;
                    listener.projectileOwner(o.entityId, map(o.owner));
                }
                break;
            case Protocol.S_ACCESS:
                if (Protocol.hasAccess(version)) {
                    Message.Access a = (Message.Access) m;
                    listener.access(a.reset, a.listsUnlocked, a.keys);
                }
                break;
            default:
                break;
        }
    }

    private void hello(Message.ServerHello h) {
        if (h.version != version) {
            if (phase == Phase.WAITING && h.version < version && Protocol.speaks(h.version)) {
                // An older server answered with its own version alone: say hello again in its version.
                version = h.version;
                out.send(new Message.ClientHello(version));
                return;
            }
            phase = Phase.INCOMPATIBLE;
            listener.incompatible(h.version);
            return;
        }
        selfOnServer = h.self;
        if (phase == Phase.WAITING) {
            phase = Phase.CONNECTED;
            listener.connected();
        }
        pendingRevision = h.revision;
        pendingCount = h.modelCount;
        pending.clear();
    }

    private void commit() {
        List<CatalogEntry> entries = new ArrayList<>(pending);
        pending.clear();
        pendingCount = -1;

        catalog.clear();
        waitingModels.clear();
        sizes.clear();
        failed.clear();
        needed.clear();
        inflight.clear();
        inflightBytes = 0;

        long total = 0;
        List<CatalogEntry> accepted = new ArrayList<>();
        List<CatalogEntry> rejected = new ArrayList<>();
        for (CatalogEntry e : entries) {
            if (catalog.containsKey(e.id) || !consistentSizes(e)
                || total + e.totalSize() > Protocol.MAX_CATALOG_BYTES) {
                rejected.add(e);
                continue;
            }
            total += e.totalSize();
            catalog.put(e.id, e);
            for (FileRef f : e.files) {
                sizes.put(f.hash, f.size);
            }
            accepted.add(e);
        }
        // Memory-only files: used by a memory-only model and by no model the disk cache may hold.
        memoryOnly.clear();
        Set<Sha256> shareable = new HashSet<>();
        Set<Sha256> referenced = new HashSet<>();
        for (CatalogEntry e : accepted) {
            for (FileRef f : e.files) {
                referenced.add(f.hash);
                (e.memoryOnly ? memoryOnly : shareable).add(f.hash);
            }
        }
        memoryOnly.removeAll(shareable);
        // Memory-only files of models no longer in the catalog are dropped, and must be fetched again if they return.
        for (Sha256 hash : new ArrayList<>(stored)) {
            if (!referenced.contains(hash) && memory.read(hash) != null) {
                stored.remove(hash);
            }
        }
        memory.retainAll(referenced);
        listener.catalogReset(accepted);
        for (CatalogEntry e : rejected) {
            listener.modelFailed(e, "repeated model, conflicting file sizes, or the catalog is too big");
        }
        Set<Sha256> queued = new HashSet<>();
        for (CatalogEntry e : accepted) {
            for (FileRef f : e.files) {
                if (stored.contains(f.hash) || memory.has(f.hash, f.size) || cache.has(f.hash, f.size)) {
                    stored.add(f.hash);
                } else if (queued.add(f.hash)) {
                    needed.add(f.hash);
                }
            }
            waitingModels.add(e.id);
        }
        checkModels();
        requestMore();
    }

    /** The same hash always comes with the same size, within the entry and against the accepted ones. */
    private boolean consistentSizes(CatalogEntry e) {
        Map<Sha256, Integer> own = new HashMap<>();
        for (FileRef f : e.files) {
            Integer known = sizes.get(f.hash);
            Integer mine = own.put(f.hash, f.size);
            if (known != null && known != f.size || mine != null && mine != f.size) {
                return false;
            }
        }
        return true;
    }

    private void requestMore() {
        List<Sha256> batch = new ArrayList<>();
        while (!needed.isEmpty() && inflight.size() < MAX_INFLIGHT_FILES && batch.size() < Protocol.MAX_REQUEST) {
            Sha256 next = needed.peek();
            int size = sizes.get(next);
            if (!inflight.isEmpty() && inflightBytes + size > MAX_INFLIGHT_BYTES) {
                break;
            }
            needed.poll();
            inflight.put(next, new Assembly(size));
            inflightBytes += size;
            batch.add(next);
        }
        if (!batch.isEmpty()) {
            out.send(new Message.RequestFiles(batch));
        }
    }

    private void chunk(Message.FileChunk c) {
        Assembly a = inflight.get(c.hash);
        if (a == null) {
            return; // not asked for, or from a catalog that was replaced
        }
        if (c.total != a.data.length || c.offset != a.received || c.data.length > a.data.length - a.received) {
            fail(c.hash, "a file arrived out of order or with the wrong size");
            return;
        }
        System.arraycopy(c.data, 0, a.data, a.received, c.data.length);
        a.received += c.data.length;
        downloaded += c.data.length;
        if (a.received < a.data.length) {
            return;
        }
        inflight.remove(c.hash);
        inflightBytes -= a.data.length;
        if (!Sha256.of(a.data)
            .equals(c.hash)) {
            fail(c.hash, "a file does not match its hash");
            return;
        }
        try {
            if (memoryOnly.contains(c.hash)) {
                memory.store(c.hash, a.data);
            } else {
                cache.store(c.hash, a.data);
            }
        } catch (IOException e) {
            fail(c.hash, "cannot store a file in the cache: " + e.getMessage());
            return;
        }
        stored.add(c.hash);
        checkModels();
        requestMore();
    }

    private void fail(Sha256 hash, String reason) {
        Assembly a = inflight.remove(hash);
        if (a != null) {
            inflightBytes -= a.data.length;
        }
        needed.remove(hash);
        failed.add(hash);
        for (CatalogEntry e : catalog.values()) {
            if (waitingModels.contains(e.id) && uses(e, hash)) {
                waitingModels.remove(e.id);
                listener.modelFailed(e, reason);
            }
        }
        requestMore();
    }

    private static boolean uses(CatalogEntry e, Sha256 hash) {
        for (FileRef f : e.files) {
            if (f.hash.equals(hash)) {
                return true;
            }
        }
        return false;
    }

    private void checkModels() {
        for (CatalogEntry e : catalog.values()) {
            if (!waitingModels.contains(e.id)) {
                continue;
            }
            boolean complete = true;
            for (FileRef f : e.files) {
                if (!stored.contains(f.hash)) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                waitingModels.remove(e.id);
                listener.modelReady(e);
            }
        }
    }
}

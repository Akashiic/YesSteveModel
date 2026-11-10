package com.akashic.futura.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.akashic.futura.net.CatalogEntry;
import com.akashic.futura.net.Message;
import com.akashic.futura.net.ModelLimits;
import com.akashic.futura.net.Protocol;
import com.akashic.futura.net.Sha256;

/**
 * The server's side of the model protocol, without Minecraft: the repository, saved selections and grants, one
 * session per online player, file transfers, the relay of states and emotes, and what the commands do. The Forge glue
 * ({@link ForgeModelServer}) feeds it events and messages and gives it a {@link Network}. Server thread only, except
 * the scan of {@link #reload}, which runs on its own thread and is applied by {@link #tick}.
 */
public final class ModelServer {

    /** How the server reaches players. */
    public interface Network {

        void send(UUID player, Message message);

        /** Online players who see this one (his entity is tracked by their clients), without himself. */
        Collection<UUID> trackers(UUID player);
    }

    /** Where log lines go. */
    public interface Log {

        void info(String line);

        void warn(String line);
    }

    /** Tunables, from {@code config/futura3d/server.properties}. */
    public static final class Settings {

        /** Per player. */
        public int bytesPerSecond = 512 * 1024;
        /** Distinct model bytes held in memory. */
        public long maxTotalBytes = 512L * 1024 * 1024;
        public int maxQueuedFiles = 4096;
        public ModelLimits limits = new ModelLimits();
    }

    /** Protocol errors from one player before his messages are ignored. */
    public static final int MAX_PROTOCOL_ERRORS = 32;

    /** A token bucket refilled every tick. */
    static final class RateLimit {

        private final double perTick;
        private final double max;
        private double tokens;

        RateLimit(double perSecond, double burst) {
            perTick = perSecond / 20;
            max = burst;
            tokens = burst;
        }

        boolean take() {
            if (tokens < 1) {
                return false;
            }
            tokens--;
            return true;
        }

        void tick() {
            tokens = Math.min(max, tokens + perTick);
        }
    }

    static final class Session {

        boolean hello;
        /** The protocol version of his hello. */
        int version = Protocol.VERSION;
        int errors;
        final TransferQueue queue;
        final RateLimit selects = new RateLimit(1, 4);
        final RateLimit emotes = new RateLimit(2, 6);
        /** Roaming messages: a slider dragged sends a few per second. */
        final RateLimit roamingUpdates = new RateLimit(10, 20);
        final RateLimit syncs = new RateLimit(10, 20);
        /**
         * Repeated hellos: a client says hello again while it has no answer (its first hello, or our answer, was lost
         * in a proxy's server switch); each one is answered with the catalog and the states again.
         */
        final RateLimit hellos = new RateLimit(0.5, 3);
        /** The extra animation he plays, as last announced (null: none), for players who start seeing him. */
        String emote;
        /** His roaming variables, for players who start seeing him; cleared when his model changes. */
        final Map<String, Float> roaming = new LinkedHashMap<>();
        /** The model key his roaming variables belong to ({@code ""} for the default model). */
        String roamingKey;

        Session(int maxQueued) {
            queue = new TransferQueue(maxQueued);
        }
    }

    private static final class Reload {

        final ModelRepository.Snapshot snapshot;
        final Consumer<String> done;

        Reload(ModelRepository.Snapshot snapshot, Consumer<String> done) {
            this.snapshot = snapshot;
            this.done = done;
        }
    }

    private final File modelsDir;
    private final PlayerStore store;
    private final Network net;
    private final Log log;
    private final Settings settings;
    private final ModelRepository repository;
    private ModelRepository.Snapshot snapshot = ModelRepository.Snapshot.empty();
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final AtomicReference<Reload> reloaded = new AtomicReference<>();
    private volatile boolean reloading;
    private ExecutorService worker;
    private int revision;

    /**
     * @param modelsDir  {@code config/futura3d/models}
     * @param playersDir {@code config/futura3d/players}
     */
    public ModelServer(File modelsDir, File playersDir, Network net, Log log, Settings settings) {
        this.modelsDir = modelsDir;
        this.store = new PlayerStore(playersDir);
        this.net = net;
        this.log = log;
        this.settings = settings;
        this.repository = new ModelRepository(settings.limits, settings.maxTotalBytes);
    }

    public ModelRepository.Snapshot snapshot() {
        return snapshot;
    }

    public PlayerStore store() {
        return store;
    }

    // ---- Repository ----

    /** Scans the models now, on this thread (server start). */
    public void loadNow() {
        ModelRepository.Snapshot s = repository.scan(modelsDir, ++revision);
        logScan(s);
        snapshot = s;
    }

    /**
     * Scans the models on a worker thread; the next {@link #tick} after it finishes swaps them in, sends the new
     * catalog to every player and calls {@code done} with a summary (on the server thread).
     */
    public void reload(Consumer<String> done) {
        if (reloading) {
            done.accept("A reload is already running");
            return;
        }
        reloading = true;
        int next = ++revision;
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "futura3d model scan");
                t.setDaemon(true);
                return t;
            });
        }
        worker.execute(() -> {
            ModelRepository.Snapshot s;
            try {
                s = repository.scan(modelsDir, next);
            } catch (RuntimeException e) {
                s = null;
                log.warn("Model scan failed: " + e);
            }
            reloaded.set(new Reload(s, done));
        });
    }

    private void logScan(ModelRepository.Snapshot s) {
        for (String line : s.log) {
            if (line.startsWith("Loaded")) {
                log.info(line);
            } else {
                log.warn(line);
            }
        }
        log.info(s.models.size() + " server models, " + s.totalBytes() + " bytes, from " + modelsDir);
    }

    private void applyReload(Reload r) {
        reloading = false;
        if (r.snapshot == null) {
            r.done.accept("The scan failed; the previous models stay");
            return;
        }
        logScan(r.snapshot);
        snapshot = r.snapshot;
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            e.getValue().queue.clear();
            if (e.getValue().hello) {
                sendCatalog(e.getKey());
            }
        }
        for (UUID player : sessions.keySet()) {
            broadcastState(player); // ids change with the files, and models may be gone
        }
        r.done.accept(snapshot.models.size() + " models loaded (" + snapshot.log.size() + " log lines)");
    }

    // ---- Players ----

    public void join(UUID player) {
        sessions.put(player, new Session(settings.maxQueuedFiles));
        store.get(player);
        drainStoreProblems();
        broadcastState(player);
    }

    public void leave(UUID player) {
        sessions.remove(player);
        store.forget(player);
    }

    public boolean hasMod(UUID player) {
        Session s = sessions.get(player);
        return s != null && s.hello;
    }

    /** Players online whose client runs the mod. */
    public List<UUID> moddedPlayers() {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().hello) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** A message that did not decode. After {@link #MAX_PROTOCOL_ERRORS} the player's messages are ignored. */
    public boolean protocolError(UUID player) {
        Session s = sessions.get(player);
        return s != null && ++s.errors == MAX_PROTOCOL_ERRORS;
    }

    public void tick() {
        Reload r = reloaded.getAndSet(null);
        if (r != null) {
            applyReload(r);
        }
        int perTick = Math.max(1, settings.bytesPerSecond / 20);
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            s.selects.tick();
            s.emotes.tick();
            s.roamingUpdates.tick();
            s.syncs.tick();
            s.hellos.tick();
            if (s.hello) {
                UUID to = e.getKey();
                ModelRepository.Snapshot current = snapshot;
                int version = s.version;
                s.queue.tick(perTick, hash -> current.content(hash, version), m -> net.send(to, m));
            }
        }
    }

    public void shutdown() {
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    // ---- Messages from clients ----

    public void received(UUID from, Message m) {
        Session s = sessions.get(from);
        if (s == null || s.errors >= MAX_PROTOCOL_ERRORS) {
            return;
        }
        if (m instanceof Message.ClientHello) {
            hello(from, s, (Message.ClientHello) m);
            return;
        }
        if (!s.hello) {
            return;
        }
        switch (m.type()) {
            case Protocol.C_REQUEST:
                ModelRepository.Snapshot current = snapshot;
                int version = s.version;
                s.queue.request(
                    ((Message.RequestFiles) m).hashes,
                    hash -> current.content(hash, version),
                    out -> net.send(from, out));
                break;
            case Protocol.C_SELECT: {
                Message.Select select = (Message.Select) m;
                if (select.initial) {
                    // The client's saved choice: only for a player the server knows nothing about.
                    if (select.key != null && !hasSavedModel(from) && s.selects.take()) {
                        select(from, select.key, select.texture);
                    }
                    break;
                }
                if (!s.selects.take() || !select(from, select.key, select.texture)) {
                    sendStates(from, Collections.singletonList(stateOf(from)));
                }
                break;
            }
            case Protocol.C_EMOTE:
                if (s.emotes.take()) {
                    relayEmote(from, ((Message.EmoteRequest) m).name, false);
                }
                break;
            case Protocol.C_ROAMING:
                if (Protocol.hasRoaming(s.version) && s.roamingUpdates.take()) {
                    Message.RoamingUpdate r = (Message.RoamingUpdate) m;
                    roaming(from, s, r.reset, r.values);
                }
                break;
            case Protocol.C_SYNC:
                if (Protocol.hasRoaming(s.version) && s.syncs.take()) {
                    relaySync(from, ((Message.SyncRequest) m).args);
                }
                break;
            default:
                break;
        }
    }

    private void hello(UUID from, Session s, Message.ClientHello h) {
        if (!Protocol.speaks(h.version)) {
            // Our hello alone tells the client which version we speak; nothing else follows.
            net.send(from, new Message.ServerHello(Protocol.VERSION, snapshot.revision, 0, from));
            return;
        }
        boolean again = s.hello;
        if (again && !s.hellos.take()) {
            return;
        }
        s.hello = true;
        s.version = h.version;
        ModelRepository.Snapshot snap = snapshot;
        List<ServerModel> offered = snap.offered(h.version);
        int memoryOnly = 0;
        for (ServerModel m : offered) {
            memoryOnly += m.entry.memoryOnly ? 1 : 0;
        }
        log.info(
            "Handshake from " + from
                + (again ? " (again)" : "")
                + ": protocol="
                + h.version
                + ", sending "
                + offered.size()
                + " models ("
                + memoryOnly
                + " memory only"
                + (offered.size() < snap.models.size() ? ", " + (snap.models.size() - offered.size())
                    + " legacy models need protocol "
                    + Protocol.MEMORY_ONLY_VERSION : "")
                + ")");
        sendCatalog(from);
        List<Message.PlayerState> states = new ArrayList<>();
        for (UUID player : sessions.keySet()) {
            states.add(stateOf(player));
        }
        sendStates(from, states);
    }

    /**
     * For commands and logs: whether the player's client answered the handshake, and in which protocol version.
     */
    public String handshakeStatus(UUID player) {
        Session s = sessions.get(player);
        if (s == null) {
            return "offline";
        }
        if (!s.hello) {
            return "no futura3d handshake from the client yet (no mod, or its hello was lost: it says hello again)";
        }
        return "protocol " + s.version + (Protocol.hasMemoryOnly(s.version) ? "" : ", legacy .ysm models not sent");
    }

    private void sendCatalog(UUID player) {
        ModelRepository.Snapshot s = snapshot;
        Session session = sessions.get(player);
        int version = session == null ? Protocol.VERSION : session.version;
        List<ServerModel> offered = s.offered(version);
        net.send(player, new Message.ServerHello(version, s.revision, offered.size(), player));
        for (ServerModel m : offered) {
            net.send(player, new Message.ModelEntry(s.revision, m.entry.withLocked(!canUse(player, m))));
        }
        net.send(player, new Message.CatalogEnd(s.revision));
    }

    // ---- Selection and permissions ----

    /**
     * Whether a player may select a model: the one permission check. Restricted models need a grant
     * ({@code /futura3d auth}). Bukkit permission plugins are not consulted (a Thermos/Crucible bridge would go here).
     */
    public boolean canUse(UUID player, ServerModel model) {
        return !model.restricted || store.get(player)
            .hasGrant(model.name);
    }

    /** The state players see: the saved choice if it still resolves and is allowed, else the default model. */
    public Message.PlayerState stateOf(UUID player) {
        PlayerStore.Record r = store.get(player);
        if (r.builtin != null && BuiltinModels.exists(r.builtin)) {
            return new Message.PlayerState(
                player,
                Protocol.BUILTIN + r.builtin,
                texture(BuiltinModels.textures(r.builtin), r.texture));
        }
        ServerModel m = r.model == null ? null : snapshot.byName(r.model);
        if (m != null && (r.forced || canUse(player, m))) {
            return new Message.PlayerState(player, m.key(), texture(m.textures, r.texture));
        }
        return new Message.PlayerState(player, null, null);
    }

    private static String texture(List<String> stems, String wanted) {
        return wanted != null && stems.contains(wanted) ? wanted : null;
    }

    private boolean hasSavedModel(UUID player) {
        PlayerStore.Record r = store.get(player);
        return r.builtin != null || r.model != null;
    }

    /** A player's own choice from the picker. */
    boolean select(UUID player, String key, String texture) {
        PlayerStore.Record r = store.get(player);
        if (key == null) {
            r.builtin = null;
            r.model = null;
            r.texture = null;
        } else if (key.startsWith(Protocol.BUILTIN)) {
            String folder = key.substring(Protocol.BUILTIN.length());
            if (!BuiltinModels.exists(folder)) {
                return false;
            }
            r.builtin = folder;
            r.model = null;
            r.texture = texture(BuiltinModels.textures(folder), texture);
        } else if (key.startsWith(Protocol.SERVER) && Sha256.isHex(key.substring(Protocol.SERVER.length()))) {
            ServerModel m = snapshot.byId(Sha256.fromHex(key.substring(Protocol.SERVER.length())));
            if (m == null || !canUse(player, m)) {
                return false;
            }
            r.builtin = null;
            r.model = m.name;
            r.texture = texture(m.textures, texture);
        } else {
            return false;
        }
        r.forced = false;
        save(player);
        broadcastState(player);
        return true;
    }

    private void save(UUID player) {
        try {
            store.save(player);
        } catch (IOException e) {
            log.warn("Could not save the model of " + player + ": " + e);
        }
    }

    private void drainStoreProblems() {
        for (String p : store.drainProblems()) {
            log.warn(p);
        }
    }

    private void broadcastState(UUID player) {
        Message.PlayerState state = stateOf(player);
        Session session = sessions.get(player);
        if (session != null) {
            // Roaming variables belong to a model: a new model starts without them (clients forget them too).
            String key = state.key == null ? "" : state.key;
            if (session.roamingKey != null && !session.roamingKey.equals(key)) {
                session.roaming.clear();
            }
            session.roamingKey = key;
        }
        List<Message.PlayerState> one = Collections.singletonList(state);
        for (UUID to : moddedPlayers()) {
            sendStates(to, one);
        }
    }

    private void sendStates(UUID to, List<Message.PlayerState> states) {
        for (int i = 0; i < states.size(); i += Protocol.MAX_STATES) {
            net.send(to, new Message.PlayerStates(states.subList(i, Math.min(states.size(), i + Protocol.MAX_STATES))));
        }
    }

    private void relayEmote(UUID player, String name, boolean includeSelf) {
        Session session = sessions.get(player);
        if (session != null) {
            session.emote = name;
        }
        Message emote = new Message.Emote(player, name);
        for (UUID to : net.trackers(player)) {
            if (!to.equals(player) && hasMod(to)) {
                net.send(to, emote);
            }
        }
        if (includeSelf && hasMod(player)) {
            net.send(player, emote);
        }
    }

    // ---- Roaming variables and ysm.sync (version 2) ----

    /** A player's roaming update: kept (at most {@link Protocol#MAX_ROAMING_VARIABLES}) and passed to his viewers. */
    void roaming(UUID player, Session s, boolean reset, Map<String, Float> values) {
        if (reset) {
            s.roaming.clear();
        }
        Map<String, Float> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Float> e : values.entrySet()) {
            if (s.roaming.containsKey(e.getKey()) || s.roaming.size() < Protocol.MAX_ROAMING_VARIABLES) {
                s.roaming.put(e.getKey(), e.getValue());
                accepted.put(e.getKey(), e.getValue());
            }
        }
        if (accepted.isEmpty() && !reset) {
            return;
        }
        Message out = new Message.Roaming(player, reset, accepted);
        for (UUID to : net.trackers(player)) {
            if (!to.equals(player) && hasRoaming(to)) {
                net.send(to, out);
            }
        }
    }

    /** A player's {@code ysm.sync}: to everyone who sees him, and back to him (upstream does the same). */
    void relaySync(UUID player, float[] args) {
        Message out = new Message.Sync(player, args);
        for (UUID to : net.trackers(player)) {
            if (!to.equals(player) && hasRoaming(to)) {
                net.send(to, out);
            }
        }
        if (hasRoaming(player)) {
            net.send(player, out);
        }
    }

    private boolean hasRoaming(UUID player) {
        Session s = sessions.get(player);
        return s != null && s.hello && Protocol.hasRoaming(s.version);
    }

    /** The roaming variables the server holds for a player (a copy; empty when unknown). */
    public Map<String, Float> roamingOf(UUID player) {
        Session s = sessions.get(player);
        return s == null ? Collections.<String, Float>emptyMap() : new LinkedHashMap<>(s.roaming);
    }

    /** The extra animation a player plays as last announced, or null. */
    public String emoteOf(UUID player) {
        Session s = sessions.get(player);
        return s == null ? null : s.emote;
    }

    /**
     * {@code viewer}'s client starts tracking {@code target} (Forge's {@code PlayerEvent.StartTracking}, after the
     * entity's spawn packet): the emote {@code target} plays and his roaming variables, so someone who comes close in
     * the middle of an emote sees it (from its start: the elapsed time is not sent).
     */
    public void startTracking(UUID viewer, UUID target) {
        if (viewer.equals(target) || !hasMod(viewer)) {
            return;
        }
        Session t = sessions.get(target);
        if (t == null) {
            return;
        }
        if (t.emote != null) {
            net.send(viewer, new Message.Emote(target, t.emote));
        }
        if (hasRoaming(viewer)) {
            List<Map.Entry<String, Float>> all = new ArrayList<>(t.roaming.entrySet());
            int i = 0;
            do {
                Map<String, Float> part = new LinkedHashMap<>();
                for (int n = 0; n < Protocol.MAX_ROAMING_PER_MESSAGE && i < all.size(); n++, i++) {
                    part.put(
                        all.get(i)
                            .getKey(),
                        all.get(i)
                            .getValue());
                }
                // The first message resets: the viewer may hold stale values from an earlier encounter.
                net.send(viewer, new Message.Roaming(target, i <= Protocol.MAX_ROAMING_PER_MESSAGE, part));
            } while (i < all.size());
        }
    }

    /**
     * {@code viewer}'s client starts tracking a projectile {@code thrower} threw (after its spawn packet): tells it
     * who, for the projectiles a 1.7.10 client cannot attribute itself (version 3).
     */
    public void projectileTracked(UUID viewer, int entityId, UUID thrower) {
        Session s = sessions.get(viewer);
        if (s != null && s.hello && Protocol.hasProjectileOwners(s.version)) {
            net.send(viewer, new Message.ProjectileOwner(entityId, thrower));
        }
    }

    // ---- Commands ----

    /** Thrown with a message for the command sender. */
    public static final class CommandError extends Exception {

        private static final long serialVersionUID = 1L;

        public CommandError(String message) {
            super(message);
        }
    }

    /**
     * {@code /futura3d set <player> <model|default> [texture]}: an operator's choice, used even if the model is
     * restricted and not granted.
     *
     * @param model {@code default}, {@code builtin:<folder>}, a server model's name or the start of its id
     * @return what was set
     */
    public String set(UUID player, String model, String texture) throws CommandError {
        PlayerStore.Record r = store.get(player);
        String result;
        if (model.equalsIgnoreCase("default")) {
            r.builtin = null;
            r.model = null;
            r.texture = null;
            r.forced = false;
            result = "the default model";
        } else if (model.startsWith(Protocol.BUILTIN)) {
            String folder = model.substring(Protocol.BUILTIN.length());
            if (!BuiltinModels.exists(folder)) {
                throw new CommandError("No built-in model " + model);
            }
            r.builtin = folder;
            r.model = null;
            r.texture = checkTexture(BuiltinModels.textures(folder), texture);
            r.forced = false;
            result = model;
        } else {
            ServerModel m = snapshot.find(model);
            if (m == null) {
                throw new CommandError("No server model " + model + " (see /futura3d list)");
            }
            r.builtin = null;
            r.model = m.name;
            r.texture = checkTexture(m.textures, texture);
            r.forced = true;
            result = m.name;
        }
        save(player);
        afterCommand(player);
        return result + (r.texture == null ? "" : " (texture " + r.texture + ")");
    }

    /** After a command changed a player's record: tell everyone if he is online, else drop it from memory. */
    private void afterCommand(UUID player) {
        if (sessions.containsKey(player)) {
            broadcastState(player);
        } else {
            store.forget(player);
        }
    }

    private static String checkTexture(List<String> stems, String texture) throws CommandError {
        if (texture == null) {
            return null;
        }
        if (!stems.contains(texture)) {
            throw new CommandError("No texture " + texture + "; the model has " + stems);
        }
        return texture;
    }

    /** {@code /futura3d auth <player> add|remove <model>}. */
    public String auth(UUID player, String model, boolean add) throws CommandError {
        ServerModel m = snapshot.find(model);
        if (m == null) {
            throw new CommandError("No server model " + model + " (see /futura3d list)");
        }
        PlayerStore.Record r = store.get(player);
        boolean changed;
        if (add) {
            changed = !r.hasGrant(m.name) && r.grants.add(m.name);
        } else {
            changed = r.grants.removeIf(g -> g.equalsIgnoreCase(m.name));
        }
        if (changed) {
            save(player);
            if (hasMod(player)) {
                sendCatalog(player); // the locked flag changed
            }
            afterCommand(player);
        }
        String what = (add ? (changed ? "granted " : "already granted ") : (changed ? "revoked " : "had no grant for "))
            + m.name;
        return m.restricted ? what : what + " (it is not restricted: anyone may select it)";
    }

    /** {@code /futura3d play <player> <animation|stop>}: tells every client that sees him, and his own. */
    public void play(UUID player, String animation) throws CommandError {
        if (animation != null && (animation.isEmpty() || !Protocol.printable(animation)
            || animation.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Protocol.MAX_ANIMATION_BYTES)) {
            throw new CommandError("Bad animation name");
        }
        relayEmote(player, animation, true);
    }

    /** {@code /futura3d list}. */
    public List<String> list() {
        List<String> lines = new ArrayList<>();
        ModelRepository.Snapshot s = snapshot;
        lines.add(s.models.size() + " server models (" + s.totalBytes() + " bytes); built-in keys: builtin:<folder>");
        for (ServerModel m : s.models) {
            CatalogEntry e = m.entry;
            lines.add(
                (m.restricted ? "[auth] " : "") + m.name
                    + " - "
                    + e.name
                    + " - "
                    + e.files.size()
                    + " files, "
                    + e.totalSize()
                    + " bytes, id "
                    + e.id.hex()
                        .substring(0, 12)
                    + ", textures "
                    + m.textures);
        }
        return lines;
    }
}

package com.akashic.futura.client.net;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.network.NetHandlerPlayClient;

import com.akashic.futura.Futura3D;
import com.akashic.futura.ModNetwork;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.client.LocalEmotes;
import com.akashic.futura.client.ModelRegistry;
import com.akashic.futura.client.ModelSelection;
import com.akashic.futura.client.PlayerModels;
import com.akashic.futura.emote.EmoteListener;
import com.akashic.futura.net.CachedModelSource;
import com.akashic.futura.net.CatalogEntry;
import com.akashic.futura.net.ClientSession;
import com.akashic.futura.net.DiskFileCache;
import com.akashic.futura.net.FileRef;
import com.akashic.futura.net.Message;
import com.akashic.futura.net.MessageCodec;
import com.akashic.futura.net.ModelLimits;
import com.akashic.futura.net.MolangSync;
import com.akashic.futura.net.Protocol;
import com.akashic.futura.net.ProtocolException;
import com.akashic.futura.net.RemoteRoaming;
import com.akashic.futura.net.RoamingTracker;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * The client's side of the model protocol in the game: one {@link ClientSession} per connection (multiplayer or the
 * integrated server), started on the first client tick in a world. On a server without the mod nothing answers the
 * hello and the client keeps its local choices; a vanilla server ignores the unknown channel. While the server runs
 * the mod, the picker's choices and the local player's emotes go to the server.
 */
public final class ClientNetwork implements ClientSession.Listener, ClientSession.Output {

    public static final ClientNetwork INSTANCE = new ClientNetwork();
    private static final int MAX_PROTOCOL_ERRORS = 32;

    private final ModelLimits limits = new ModelLimits();
    private final Queue<Runnable> mainThread = new ConcurrentLinkedQueue<>();
    private DiskFileCache cache;
    private ExecutorService worker;
    private NetHandlerPlayClient handler;
    private ClientSession session;
    private int generation;
    private int errors;
    private int ready;
    private boolean contractWarned;

    // Roaming variables and ysm.sync (protocol 2)
    private final RoamingTracker roamingOut = new RoamingTracker();
    private final RemoteRoaming remoteRoaming = new RemoteRoaming();
    /** The roaming version written into each animator of another player. */
    private final Map<PlayerAnimator, Integer> written = new WeakHashMap<>();
    /** Each player's model key as the server last said, to forget his roaming variables when it changes. */
    private final Map<UUID, String> keys = new HashMap<>();
    private PlayerAnimator localAnimatorSeen;
    /** The server was told the local player plays an emote and not yet that it stopped. */
    private boolean emoteAnnounced;
    private long ticks;
    /** Token bucket for the local model's ysm.sync: 10 a second, bursts of 20 (the server's limit). */
    private double syncTokens = 20;

    private final EmoteListener emotes = new EmoteListener() {

        @Override
        public void played(String name) {
            emoteAnnounced = true;
            send(new Message.EmoteRequest(name));
        }

        @Override
        public void stopped() {
            emoteAnnounced = false;
            send(new Message.EmoteRequest(null));
        }
    };

    private final ModelSelection.Handler selection = (key, texture) -> send(new Message.Select(key, texture));

    private ClientNetwork() {}

    /** Init, client only. */
    public static void register() {
        INSTANCE.cache = new DiskFileCache(new File(Minecraft.getMinecraft().mcDataDir, "futura3d/cache"));
        ModNetwork.channel()
            .register(INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
        MolangSync.setEmitter(INSTANCE::emitSync);
        // ysm.sync: the animator's SyncSink (client/ModelSync) sends the local player's values through this
        // transport while the server relays syncs; what comes back runs the model's @sync handlers.
        com.akashic.futura.client.ModelSync.setTransport(new com.akashic.futura.client.ModelSync.Transport() {

            @Override
            public boolean connected() {
                return INSTANCE.session != null && INSTANCE.session.hasRoaming();
            }

            @Override
            public void send(float[] values) {
                INSTANCE.sendSync(values);
            }
        });
        MolangSync.setReceiver((target, args) -> {
            if (target instanceof PlayerAnimator) {
                ((PlayerAnimator) target).dispatchSync(args);
            }
        });
    }

    /** For logs and the harness: connection, catalog and download progress. */
    public String status() {
        if (session == null) {
            return "no connection";
        }
        return (session.connected() ? "server runs the mod" : "no answer from the server") + ", "
            + session.catalog()
                .size()
            + " models, "
            + ready
            + " ready, "
            + session.pendingFiles()
            + " files pending, "
            + session.downloadedBytes()
            + " bytes downloaded";
    }

    public boolean isConnected() {
        return session != null && session.connected();
    }

    // ---- Connection lifecycle ----

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        NetHandlerPlayClient now = Minecraft.getMinecraft()
            .getNetHandler();
        if (now != handler) {
            end();
            handler = now;
            if (now != null) {
                session = new ClientSession(cache, this, this);
                session.setLocalPlayer(Minecraft.getMinecraft().thePlayer.getUniqueID());
                errors = 0;
                session.start();
            }
        }
        Runnable r;
        while ((r = mainThread.poll()) != null) {
            r.run();
        }
        ticks++;
        syncTokens = Math.min(20, syncTokens + 0.5);
        if (session != null && session.connected()) {
            try {
                pollLocal();
                applyRemoteRoaming();
            } catch (RuntimeException e) {
                Futura3D.LOG.error("Roaming variables failed", e);
            }
        }
    }

    // ---- Roaming variables, the emote state and ysm.sync (protocol 2) ----

    /** The local player: an emote that ended by itself, and roaming variables that changed. */
    private void pollLocal() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !PlayerModels.INSTANCE.active()) {
            return;
        }
        PlayerAnimator a = PlayerModels.INSTANCE.animator(mc.thePlayer);
        if (emoteAnnounced && (a == null || !a.isPlayingExtra())) {
            // A play-once emote ended: the server stops sending it to players who come close.
            emoteAnnounced = false;
            send(new Message.EmoteRequest(null));
        }
        if (!session.hasRoaming()) {
            return;
        }
        if (a != localAnimatorSeen) {
            localAnimatorSeen = a; // a new model: its variables start over
            roamingOut.reset();
        }
        Map<String, Float> current = new HashMap<>();
        if (a != null) {
            a.variables()
                .roamingNumbers(current);
        }
        for (Message m : roamingOut.poll(current, ticks)) {
            send(m);
        }
    }

    /** Writes the other players' relayed roaming variables into their animators (again after a new animator). */
    private void applyRemoteRoaming() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || remoteRoaming.players() == 0 || !PlayerModels.INSTANCE.active()) {
            return;
        }
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof AbstractClientPlayer) || o == mc.thePlayer) {
                continue;
            }
            AbstractClientPlayer p = (AbstractClientPlayer) o;
            int version = remoteRoaming.version(p.getUniqueID());
            if (version == 0) {
                continue;
            }
            PlayerAnimator a = PlayerModels.INSTANCE.animator(p);
            if (a == null) {
                continue;
            }
            Integer done = written.get(a);
            if (done != null && done == version) {
                continue;
            }
            writeRoaming(a, remoteRoaming.of(p.getUniqueID()));
            written.put(a, version);
        }
    }

    /** Makes an animator's roaming variables exactly {@code values}. */
    static void writeRoaming(PlayerAnimator a, Map<String, Float> values) {
        Map<String, Float> now = new HashMap<>();
        a.variables()
            .roamingNumbers(now);
        for (String name : now.keySet()) {
            if (!values.containsKey(name)) {
                a.variables()
                    .remove("roaming." + name);
            }
        }
        for (Map.Entry<String, Float> e : values.entrySet()) {
            a.variables()
                .set("roaming." + e.getKey(), com.akashic.futura.molang.MolangValue.of(e.getValue()));
        }
    }

    /** {@code ysm.sync} from a model: the local player's goes to the server, or runs locally without one. */
    private void emitSync(Object source, float[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !PlayerModels.INSTANCE.active()
            || source != PlayerModels.INSTANCE.animator(mc.thePlayer)) {
            return; // other players' models run theirs when the server relays the event
        }
        if (syncTokens < 1) {
            return; // a model calling it every frame: dropped, as the server would
        }
        syncTokens--;
        if (session != null && session.hasRoaming()) {
            send(new Message.SyncRequest(args));
        } else {
            MolangSync.receive(source, args);
        }
    }

    /** The local player's {@code ysm.sync} values to the server (rate-limited as the server would). */
    private void sendSync(float[] values) {
        if (syncTokens < 1) {
            return;
        }
        syncTokens--;
        send(new Message.SyncRequest(values));
    }

    /** For the harness: the roaming variables the server relayed for a player. */
    public Map<String, Float> remoteRoamingOf(UUID player) {
        return remoteRoaming.of(player);
    }

    /** For the harness: what the server was last told about the local player's roaming variables. */
    public Map<String, Float> sentRoaming() {
        return roamingOut.sent();
    }

    private void end() {
        if (session != null && session.connected()) {
            LocalEmotes.INSTANCE.removeListener(emotes);
            contract("disconnect", () -> {
                ModelSelection.INSTANCE.setHandler(null);
                ModelRegistry.INSTANCE.clearServerModels();
                ModelSelection.INSTANCE.clearRemote();
            });
            Futura3D.LOG.info("Left the model server ({})", status());
        }
        session = null;
        ready = 0;
        generation++;
        mainThread.clear();
        roamingOut.reset();
        remoteRoaming.clear();
        written.clear();
        keys.clear();
        localAnimatorSeen = null;
        emoteAnnounced = false;
        PlayerModels.INSTANCE.externalStates()
            .clear(); // UUIDs of another world
    }

    @SubscribeEvent
    public void onPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
        if (session == null || errors >= MAX_PROTOCOL_ERRORS) {
            return;
        }
        Message m;
        try {
            m = MessageCodec.decodeFromServer(ModNetwork.payload(event.packet));
        } catch (ProtocolException e) {
            if (++errors == MAX_PROTOCOL_ERRORS) {
                Futura3D.LOG
                    .warn("Ignoring the server's model messages after repeated protocol errors: {}", e.getMessage());
            }
            return;
        }
        session.handle(m);
    }

    @Override
    public void send(Message message) {
        if (handler != null) {
            ModNetwork.channel()
                .sendToServer(ModNetwork.packet(message));
        }
    }

    // ---- What the session reports ----

    @Override
    public void connected() {
        Futura3D.LOG.info("The server runs {}: models and selections come from it", Futura3D.NAME);
        LocalEmotes.INSTANCE.addListener(emotes);
        contract("connect", () -> {
            // The saved local choice, for a server that has nothing saved for this player yet.
            String key = ModelSelection.INSTANCE.localKey();
            if (key != null && com.akashic.futura.net.Protocol.validKey(key)) {
                String texture = ModelSelection.INSTANCE.localTexture();
                send(
                    new Message.Select(
                        key,
                        texture != null && com.akashic.futura.net.Protocol.printable(texture) ? texture : null,
                        true));
            }
            ModelSelection.INSTANCE.setHandler(selection);
        });
    }

    @Override
    public void incompatible(int serverVersion) {
        Futura3D.LOG.warn(
            "The server runs another version of the model protocol ({}, this client {}): local models only",
            serverVersion,
            Protocol.VERSION);
    }

    @Override
    public void catalogReset(List<CatalogEntry> catalog) {
        long bytes = 0;
        for (CatalogEntry e : catalog) {
            bytes += e.totalSize();
        }
        Futura3D.LOG.info("Server catalog: {} models, {} bytes in all", catalog.size(), bytes);
        ready = 0;
        generation++;
        mainThread.clear();
        contract("catalog", () -> ModelRegistry.INSTANCE.clearServerModels());
    }

    @Override
    public void modelReady(CatalogEntry entry) {
        // The files are verified; check them by kind off the client thread, then register on it.
        int gen = generation;
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "futura3d model check");
                t.setDaemon(true);
                return t;
            });
        }
        worker.execute(() -> {
            String problem = check(entry);
            mainThread.add(() -> {
                if (gen != generation) {
                    return;
                }
                if (problem != null) {
                    Futura3D.LOG.warn("Server model {} refused: {}", entry, problem);
                    return;
                }
                ready++;
                Futura3D.LOG.info("Server model ready: {}", entry);
                contract(
                    "register",
                    () -> ModelRegistry.INSTANCE
                        .registerServerModel(entry.key(), new CachedModelSource(entry, cache), entry.locked));
            });
        });
    }

    private String check(CatalogEntry entry) {
        try {
            Map<String, byte[]> files = new TreeMap<>();
            for (FileRef f : entry.files) {
                byte[] data = cache.read(f.hash);
                if (data == null) {
                    return f.path + " is missing from the cache";
                }
                files.put(f.path, data);
            }
            limits.checkFiles(files);
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getMessage();
        }
    }

    @Override
    public void modelFailed(CatalogEntry entry, String reason) {
        Futura3D.LOG.warn("Server model {} not available: {}", entry, reason);
    }

    @Override
    public void state(UUID player, String key, String texture) {
        String now = key == null ? "" : key;
        String before = keys.put(player, now);
        if (before != null && !before.equals(now)) {
            remoteRoaming.forget(player); // his variables belonged to the old model
        }
        contract("state", () -> ModelSelection.INSTANCE.select(player, key, texture));
    }

    @Override
    public void roaming(UUID player, boolean reset, Map<String, Float> values) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && mc.thePlayer.getUniqueID()
            .equals(player)) {
            return; // the local player's own are the source, not a copy
        }
        remoteRoaming.apply(player, reset, values);
    }

    @Override
    public void sync(UUID player, float[] args) {
        AbstractClientPlayer p = player(player);
        PlayerAnimator a = p == null || !PlayerModels.INSTANCE.active() ? null : PlayerModels.INSTANCE.animator(p);
        if (a != null) {
            MolangSync.receive(a, args);
        }
    }

    private static AbstractClientPlayer player(UUID id) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return null;
        }
        for (Object o : mc.theWorld.playerEntities) {
            if (o instanceof AbstractClientPlayer && ((AbstractClientPlayer) o).getUniqueID()
                .equals(id)) {
                return (AbstractClientPlayer) o;
            }
        }
        return null;
    }

    @Override
    public void emote(UUID player, String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !PlayerModels.INSTANCE.active()) {
            return;
        }
        for (Object o : mc.theWorld.playerEntities) {
            if (o instanceof AbstractClientPlayer && ((AbstractClientPlayer) o).getUniqueID()
                .equals(player)) {
                PlayerAnimator animator = PlayerModels.INSTANCE.animator((AbstractClientPlayer) o);
                if (animator == null) {
                    return;
                }
                if (name == null) {
                    animator.stopExtra();
                    if (o == mc.thePlayer) {
                        emoteAnnounced = false;
                    }
                } else if (animator.playExtra(name) && o == mc.thePlayer) {
                    emoteAnnounced = true; // the server plays it on us (/futura3d play): tell it when it ends
                }
                return;
            }
        }
    }

    /** Calls into {@link ModelRegistry}/{@link ModelSelection}, which phase 4b implements. */
    private void contract(String what, Runnable call) {
        try {
            call.run();
        } catch (UnsupportedOperationException e) {
            if (!contractWarned) {
                contractWarned = true;
                Futura3D.LOG.warn(
                    "The client model registry is not implemented yet ({}): server models are downloaded but not drawn",
                    what);
            }
        } catch (RuntimeException e) {
            Futura3D.LOG.error("Model {} failed", what, e);
        }
    }
}

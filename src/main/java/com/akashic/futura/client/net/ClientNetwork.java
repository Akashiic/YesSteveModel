package com.akashic.futura.client.net;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
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
import com.akashic.futura.net.ProtocolException;

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

    private final EmoteListener emotes = new EmoteListener() {

        @Override
        public void played(String name) {
            send(new Message.EmoteRequest(name));
        }

        @Override
        public void stopped() {
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
                errors = 0;
                session.start();
            }
        }
        Runnable r;
        while ((r = mainThread.poll()) != null) {
            r.run();
        }
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
        contract("connect", () -> ModelSelection.INSTANCE.setHandler(selection));
    }

    @Override
    public void incompatible(int serverVersion) {
        Futura3D.LOG.warn(
            "The server runs another version of the model protocol ({}, this client {}): local models only",
            serverVersion,
            com.akashic.futura.net.Protocol.VERSION);
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
        contract("state", () -> ModelSelection.INSTANCE.select(player, key, texture));
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
                if (name == null) {
                    animator.stopExtra();
                } else {
                    animator.playExtra(name);
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

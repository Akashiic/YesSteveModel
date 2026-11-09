package com.akashic.futura.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.akashic.futura.net.CatalogEntry;
import com.akashic.futura.net.ClientSession;
import com.akashic.futura.net.MemoryFileCache;
import com.akashic.futura.net.Message;
import com.akashic.futura.net.MessageCodec;
import com.akashic.futura.net.Protocol;
import com.akashic.futura.net.ProtocolException;
import com.akashic.futura.net.Sha256;

/** The server and real client sessions talking through the codec, with fake players. */
class ModelServerTest {

    @TempDir
    Path tmp;

    /** One fake client: its session, its cache and what it heard. */
    final class Client implements ClientSession.Listener, ClientSession.Output {

        final UUID id;
        final MemoryFileCache cache = new MemoryFileCache();
        final ClientSession session = new ClientSession(cache, this, this);
        final Map<UUID, String> keys = new HashMap<>();
        final Map<UUID, String> textures = new HashMap<>();
        final List<String> emotes = new ArrayList<>();
        final Map<String, CatalogEntry> ready = new LinkedHashMap<>();
        List<CatalogEntry> catalog = new ArrayList<>();
        int resets;
        int received;

        Client(UUID id) {
            this.id = id;
        }

        @Override
        public void send(Message m) {
            byte[] wire = MessageCodec.encode(m);
            toServer.add(() -> {
                try {
                    server.received(id, MessageCodec.decodeFromClient(wire));
                } catch (ProtocolException e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Override
        public void connected() {}

        @Override
        public void incompatible(int v) {}

        @Override
        public void catalogReset(List<CatalogEntry> c) {
            catalog = c;
            ready.clear();
            resets++;
        }

        @Override
        public void modelReady(CatalogEntry e) {
            ready.put(e.key(), e);
        }

        @Override
        public void modelFailed(CatalogEntry e, String reason) {
            throw new AssertionError(e + ": " + reason);
        }

        @Override
        public void state(UUID p, String key, String texture) {
            keys.put(p, key);
            textures.put(p, texture);
        }

        @Override
        public void emote(UUID p, String name) {
            emotes.add(
                p.toString()
                    .substring(0, 4) + ":"
                    + name);
        }

        CatalogEntry entry(String name) {
            for (CatalogEntry e : catalog) {
                if (e.name.toLowerCase()
                    .startsWith(name)) {
                    return e;
                }
            }
            throw new AssertionError("no " + name + " in " + catalog);
        }
    }

    private final Map<UUID, Client> clients = new HashMap<>();
    private final Deque<Runnable> toServer = new ArrayDeque<>();
    private final Deque<Runnable> toClients = new ArrayDeque<>();
    private final Map<UUID, Set<UUID>> trackers = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private ModelServer server;
    private final UUID a = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final UUID b = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private final UUID vanilla = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private int vanillaMessages;

    private final ModelServer.Network network = new ModelServer.Network() {

        @Override
        public void send(UUID player, Message m) {
            byte[] wire = MessageCodec.encode(m);
            assertTrue(wire.length <= Protocol.TARGET_PAYLOAD);
            Client c = clients.get(player);
            if (c == null) {
                vanillaMessages++;
                return;
            }
            toClients.add(() -> {
                try {
                    c.received++;
                    c.session.handle(MessageCodec.decodeFromServer(wire));
                } catch (ProtocolException e) {
                    throw new AssertionError(e);
                }
            });
        }

        @Override
        public Collection<UUID> trackers(UUID player) {
            return trackers.getOrDefault(player, Collections.<UUID>emptySet());
        }
    };

    private final ModelServer.Log log = new ModelServer.Log() {

        @Override
        public void info(String line) {}

        @Override
        public void warn(String line) {
            warnings.add(line);
        }
    };

    private ModelServer newServer(ModelServer.Settings settings) {
        return new ModelServer(
            tmp.resolve("models")
                .toFile(),
            tmp.resolve("players")
                .toFile(),
            network,
            log,
            settings);
    }

    @BeforeEach
    void setUp() throws IOException {
        ServerTestSupport.copyFolder("misc/1_alex", tmp.resolve("models/alex"));
        ServerTestSupport.zipFolder("misc/2_steve", tmp.resolve("models/auth/steve.zip"), "");
        ModelServer.Settings settings = new ModelServer.Settings();
        settings.bytesPerSecond = 4 << 20;
        server = newServer(settings);
        server.loadNow();
    }

    /** Delivers messages both ways until quiet. */
    private void deliver() {
        while (!toServer.isEmpty() || !toClients.isEmpty()) {
            while (!toClients.isEmpty()) {
                toClients.poll()
                    .run();
            }
            while (!toServer.isEmpty()) {
                toServer.poll()
                    .run();
            }
        }
    }

    private int run(int maxTicks) {
        for (int t = 1; t <= maxTicks; t++) {
            server.tick();
            deliver();
            boolean busy = false;
            for (Client c : clients.values()) {
                busy |= c.session.pendingFiles() > 0;
            }
            if (!busy) {
                return t;
            }
        }
        return maxTicks;
    }

    private Client connect(UUID id) {
        Client c = new Client(id);
        clients.put(id, c);
        server.join(id);
        c.session.start();
        deliver();
        return c;
    }

    @Test
    void joinDownloadSelectAndRestore() {
        Client ca = connect(a);
        assertEquals(2, ca.catalog.size());
        assertTrue(ca.entry("steve").locked, "restricted and not granted");
        assertFalse(ca.entry("alex").locked);
        run(200);
        assertEquals(2, ca.ready.size(), "every model is downloaded, locked ones too");
        assertTrue(ca.keys.containsKey(a));
        assertNull(ca.keys.get(a), "default model at first");

        Client cb = connect(b);
        run(200);
        server.join(vanilla);
        deliver();
        assertNull(cb.keys.get(vanilla));
        assertTrue(cb.keys.containsKey(vanilla), "states of players without the mod are sent too");

        // a selects alex with its texture: everyone with the mod hears it
        CatalogEntry alex = ca.entry("alex");
        ca.send(new Message.Select(alex.key(), "gsl"));
        deliver();
        assertEquals(alex.key(), ca.keys.get(a));
        assertEquals("gsl", ca.textures.get(a));
        assertEquals(alex.key(), cb.keys.get(a));
        assertEquals(0, vanillaMessages, "a vanilla client gets nothing");

        // a texture the model does not have falls back to the default texture
        ca.send(new Message.Select(alex.key(), "nope"));
        deliver();
        assertNull(cb.textures.get(a));

        // restricted model without a grant: refused, the current state is echoed back
        CatalogEntry steve = ca.entry("steve");
        cb.keys.clear();
        ca.send(new Message.Select(steve.key(), null));
        deliver();
        assertEquals(alex.key(), ca.keys.get(a));
        assertFalse(cb.keys.containsKey(a), "a refusal is only told to the player");

        // with a grant the catalog is resent unlocked and the choice goes through
        int resets = ca.resets;
        server.tick(); // refill the selection rate limit
        for (int i = 0; i < 40; i++) {
            server.tick();
        }
        assertTrue(assertDoesNotThrow(() -> server.auth(a, "steve", true)).startsWith("granted"));
        deliver();
        assertEquals(resets + 1, ca.resets);
        assertFalse(ca.entry("steve").locked);
        run(10);
        assertEquals(2, ca.ready.size(), "re-registered from the cache");
        ca.send(new Message.Select(steve.key(), null));
        deliver();
        assertEquals(steve.key(), cb.keys.get(a));

        // revoking it drops a back to the default model
        quietly(() -> server.auth(a, "steve", false));
        deliver();
        assertNull(cb.keys.get(a));

        // built-in keys
        for (int i = 0; i < 40; i++) {
            server.tick();
        }
        ca.send(new Message.Select("builtin:wine_fox/18_wedding", null));
        deliver();
        assertEquals("builtin:wine_fox/18_wedding", cb.keys.get(a));
        ca.send(new Message.Select("builtin:no_such_model", null));
        deliver();
        assertEquals("builtin:wine_fox/18_wedding", cb.keys.get(a));

        // saved per UUID: a new server restores it
        server.leave(a);
        ModelServer restarted = newServer(new ModelServer.Settings());
        restarted.loadNow();
        assertEquals("builtin:wine_fox/18_wedding", restarted.stateOf(a).key);
    }

    @Test
    void theSavedLocalChoiceOnlyFillsAnEmptyRecord() throws ModelServer.CommandError {
        Client ca = connect(a);
        ca.send(new Message.Select("builtin:misc/1_alex", null, true));
        deliver();
        assertEquals("builtin:misc/1_alex", ca.keys.get(a));
        // something is saved now: a later initial choice does not override it, nor an operator's
        ca.send(new Message.Select("builtin:default", null, true));
        deliver();
        assertEquals("builtin:misc/1_alex", ca.keys.get(a));
        server.set(a, "alex", null);
        deliver();
        ca.send(new Message.Select("builtin:default", null, true));
        deliver();
        assertEquals(
            ca.entry("alex")
                .key(),
            ca.keys.get(a));
        // an invalid initial choice is dropped quietly
        Client cb = connect(b);
        int before = cb.received;
        cb.send(new Message.Select("builtin:no_such_model", null, true));
        deliver();
        assertEquals(before, cb.received);
    }

    @Test
    void operatorCommands() throws ModelServer.CommandError {
        Client ca = connect(a);
        Client cb = connect(b);
        run(200);
        // set forces a restricted model without a grant
        assertEquals("steve", server.set(a, "steve", null));
        deliver();
        assertEquals(
            ca.entry("steve")
                .key(),
            cb.keys.get(a));
        assertTrue(ca.entry("steve").locked, "still not selectable by the player himself");
        assertEquals("builtin:default (texture blue)", server.set(a, "builtin:default", "blue"));
        deliver();
        assertEquals("builtin:default", cb.keys.get(a));
        assertEquals("blue", cb.textures.get(a));
        assertThrows(ModelServer.CommandError.class, () -> server.set(a, "builtin:nope", null));
        assertThrows(ModelServer.CommandError.class, () -> server.set(a, "nope", null));
        assertThrows(ModelServer.CommandError.class, () -> server.set(a, "alex", "nope"));
        server.set(a, "default", null);
        deliver();
        assertNull(cb.keys.get(a));

        // an offline player's choice is saved for when he joins
        UUID offline = UUID.randomUUID();
        server.set(offline, "alex", null);
        assertEquals(
            ca.entry("alex")
                .key(),
            server.stateOf(offline).key);

        // play: the trackers and the player himself; a client's own emote: the trackers only
        trackers.put(a, new HashSet<>(Collections.singletonList(b)));
        server.play(a, "extra0");
        server.play(a, null);
        deliver();
        assertEquals(java.util.Arrays.asList("aaaa:extra0", "aaaa:null"), cb.emotes);
        assertEquals(java.util.Arrays.asList("aaaa:extra0", "aaaa:null"), ca.emotes);
        ca.send(new Message.EmoteRequest("wave"));
        deliver();
        assertEquals("aaaa:wave", cb.emotes.get(2));
        assertEquals(2, ca.emotes.size());
        // nobody tracks b: his emote reaches nobody
        cb.send(new Message.EmoteRequest("wave"));
        deliver();
        assertEquals(2, ca.emotes.size());

        assertTrue(
            server.list()
                .get(0)
                .startsWith("2 server models"));
    }

    @Test
    void reloadSendsTheNewCatalog() throws Exception {
        Client ca = connect(a);
        run(200);
        server.set(a, "alex", null);
        deliver();
        String oldKey = ca.keys.get(a);
        ServerTestSupport.copyFolder("misc/3_default_boy", tmp.resolve("models/boy"));
        // alex changes: new id
        java.nio.file.Files.write(tmp.resolve("models/alex/lang/extra.json"), "{}".getBytes());
        String[] done = new String[1];
        server.reload(s -> done[0] = s);
        for (int i = 0; i < 500 && done[0] == null; i++) {
            Thread.sleep(10);
            server.tick();
            deliver();
        }
        assertTrue(done[0].startsWith("3 models"), done[0]);
        assertEquals(3, ca.catalog.size());
        run(200);
        assertEquals(3, ca.ready.size());
        String newKey = ca.keys.get(a);
        assertFalse(newKey.equals(oldKey), "the saved choice follows the model by name");
        assertEquals(
            ca.entry("alex")
                .key(),
            newKey);
        server.shutdown();
    }

    @Test
    void ratesAndVersions() {
        ModelServer.Settings slow = new ModelServer.Settings();
        slow.bytesPerSecond = 200_000; // 10 000 bytes per tick
        server = newServer(slow);
        server.loadNow();
        Client ca = connect(a);
        long total = 0;
        for (CatalogEntry e : ca.catalog) {
            total += e.totalSize();
        }
        int ticks = run(100_000);
        assertEquals(2, ca.ready.size());
        assertTrue(ticks >= total / 10_000 - 2, ticks + " ticks for " + total + " bytes");

        // a client of another protocol version only hears our hello
        Client old = new Client(b);
        clients.put(b, old);
        server.join(b);
        old.send(new Message.ClientHello(Protocol.VERSION + 1));
        deliver();
        assertEquals(1, old.received);
        assertFalse(server.hasMod(b));

        // requests before the hello, and file requests for unknown hashes
        Client late = new Client(vanilla);
        clients.put(vanilla, late);
        server.join(vanilla);
        late.send(new Message.RequestFiles(Collections.singletonList(Sha256.of(new byte[] { 1 }))));
        deliver();
        assertEquals(0, late.received);
        // protocol errors: after the limit the player is ignored
        for (int i = 0; i < ModelServer.MAX_PROTOCOL_ERRORS - 1; i++) {
            assertFalse(server.protocolError(vanilla));
        }
        assertTrue(server.protocolError(vanilla));
        late.session.start();
        deliver();
        assertEquals(0, late.received);
    }

    private static <T> T assertDoesNotThrow(ThrowingSupplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingSupplier<T> {

        T get() throws Exception;
    }

    private static void quietly(ThrowingRunnable r) {
        try {
            r.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingRunnable {

        void run() throws Exception;
    }
}

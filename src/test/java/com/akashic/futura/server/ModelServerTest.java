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
        final Map<UUID, Map<String, Float>> roaming = new HashMap<>();
        final List<String> roamingLog = new ArrayList<>();
        final List<String> syncs = new ArrayList<>();
        final com.akashic.futura.net.ModelAccess access = new com.akashic.futura.net.ModelAccess();
        int accessMessages;

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

        @Override
        public void roaming(UUID p, boolean reset, Map<String, Float> values) {
            Map<String, Float> mine = roaming.computeIfAbsent(p, k -> new LinkedHashMap<>());
            if (reset) {
                mine.clear();
            }
            mine.putAll(values);
            roamingLog.add(
                p.toString()
                    .substring(0, 4) + (reset ? ":reset" : "")
                    + ":"
                    + values);
        }

        @Override
        public void sync(UUID p, float[] args) {
            syncs.add(
                p.toString()
                    .substring(0, 4) + ":"
                    + java.util.Arrays.toString(args));
        }

        @Override
        public void access(boolean reset, boolean listsUnlocked, List<String> keys) {
            accessMessages++;
            access.apply(reset, listsUnlocked, keys);
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
    /** What was sent to players without a fake client (vanilla, or a raw old client). */
    private final List<Message> toNobody = new ArrayList<>();

    private final ModelServer.Network network = new ModelServer.Network() {

        @Override
        public void send(UUID player, Message m) {
            byte[] wire = MessageCodec.encode(m);
            assertTrue(wire.length <= Protocol.TARGET_PAYLOAD);
            Client c = clients.get(player);
            if (c == null) {
                vanillaMessages++;
                toNobody.add(m);
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

    /** False: the older tests run without {@code requirePermission} (only restricted models need a grant). */
    private boolean permissionMode;
    /** The permission nodes each player has, for permission-mode tests. */
    private final Map<UUID, Set<String>> nodes = new HashMap<>();

    private ModelServer newServer(ModelServer.Settings settings) {
        if (!permissionMode) {
            settings.requirePermission = false;
        }
        ModelServer created = new ModelServer(
            tmp.resolve("models")
                .toFile(),
            tmp.resolve("players")
                .toFile(),
            network,
            log,
            settings);
        created.setPermissions(
            (player, node) -> nodes.getOrDefault(player, Collections.<String>emptySet())
                .contains(node));
        return created;
    }

    private void grantNode(UUID player, String node) {
        nodes.computeIfAbsent(player, k -> new HashSet<>())
            .add(node);
    }

    /** A server where every model needs its permission node (the default settings). */
    private void permissionServer() {
        permissionMode = true;
        ModelServer.Settings settings = new ModelServer.Settings();
        settings.bytesPerSecond = 4 << 20;
        settings.permissionCheckTicks = 10;
        server = newServer(settings);
        server.loadNow();
    }

    @Test
    void playersWhoLeaveLeaveNothingInMemory() {
        for (int i = 0; i < 500; i++) {
            UUID p = new UUID(42, i);
            Client c = connect(p);
            server.received(p, new Message.Select("builtin:misc/2_steve", null));
            server.received(p, new Message.EmoteRequest("extra0"));
            deliver();
            server.leave(p);
            clients.remove(p);
        }
        server.accessReport(new UUID(43, 1)); // an offline player
        server.tick();
        assertEquals(0, server.sessionCount());
        assertEquals(
            0,
            server.store()
                .cached(),
            "no player record stays cached after leaving");
    }

    @Test
    void withPermissionsEveryModelIsLockedButTheFreeOnes() {
        permissionServer();
        Client ca = connect(a);
        run(200);
        assertTrue(ca.entry("alex").locked, "a server model without its node");
        assertTrue(ca.entry("steve").locked);
        assertTrue(ca.access.known());
        assertTrue(ca.access.locked("builtin:wine_fox/18_wedding"), "built-in models need a node too");
        assertTrue(ca.access.locked("builtin:default"));
        assertFalse(ca.access.locked("builtin:misc/1_alex"), "Steve and Alex are free");
        assertFalse(ca.access.locked("builtin:misc/2_steve"));
        assertEquals(
            "futura3d.playermodel.alex",
            ModelServer.node(
                server.snapshot()
                    .byName("alex")));
        assertEquals(
            "futura3d.playermodel.builtin.wine_fox.18_wedding",
            ModelServer.builtinNode("wine_fox/18_wedding"));

        // locked selections are refused (the client hears its old state again); the own skin always works
        ca.send(
            new Message.Select(
                server.snapshot()
                    .byName("alex")
                    .key(),
                null));
        deliver();
        assertNull(ca.keys.get(a));
        ca.send(new Message.Select("builtin:wine_fox/18_wedding", null));
        deliver();
        assertNull(ca.keys.get(a));
        ca.send(new Message.Select("builtin:misc/2_steve", null));
        deliver();
        assertEquals("builtin:misc/2_steve", ca.keys.get(a));
        ca.send(new Message.Select(null, null));
        deliver();
        assertNull(ca.keys.get(a));
    }

    @Test
    void aNodeGrantedLaterUnlocksOnlyThatModelAndLosingItRestoresTheDefault() {
        permissionServer();
        Client ca = connect(a);
        Client cb = connect(b);
        run(200);
        String alex = server.snapshot()
            .byName("alex")
            .key();
        String steve = server.snapshot()
            .byName("steve")
            .key();
        int before = ca.accessMessages;
        grantNode(a, "futura3d.playermodel.alex");
        grantNode(a, "futura3d.playermodel.builtin.wine_fox.18_wedding");
        ticks(20); // a permission check
        assertTrue(ca.accessMessages > before, "the change is sent without a reconnect");
        assertFalse(ca.access.locked(alex));
        assertTrue(ca.access.locked(steve), "only the granted model");
        assertFalse(ca.access.locked("builtin:wine_fox/18_wedding"));
        assertTrue(ca.access.locked("builtin:wine_fox/01_taisho_maid"));
        assertTrue(cb.access.locked(alex), "only for that player");

        ca.send(new Message.Select(alex, null));
        deliver();
        assertEquals(alex, cb.keys.get(a));

        // nothing changed: no new message
        int now = ca.accessMessages;
        ticks(20);
        assertEquals(now, ca.accessMessages);

        // the node is taken away: back to the default look for everyone
        nodes.get(a)
            .remove("futura3d.playermodel.alex");
        ticks(20);
        assertTrue(ca.access.locked(alex));
        assertNull(cb.keys.get(a));
        assertNull(ca.keys.get(a));

        // an operator's /futura3d set still wins
        quietly(() -> server.set(a, "builtin:wine_fox/01_taisho_maid", null));
        deliver();
        ticks(20);
        assertEquals("builtin:wine_fox/01_taisho_maid", cb.keys.get(a));
    }

    @Test
    void wildcardsGrantsAndFreeModels() {
        permissionServer();
        server.settings().freeModels.add("steve");
        Client ca = connect(a);
        Client cb = connect(b);
        run(200);
        String alex = server.snapshot()
            .byName("alex")
            .key();
        assertFalse(
            ca.access.locked(
                server.snapshot()
                    .byName("steve")
                    .key()),
            "free by name");
        grantNode(a, ModelServer.NODE_ALL);
        grantNode(b, ModelServer.NODE_ALL_BUILTIN);
        ticks(20);
        assertFalse(ca.access.locked(alex));
        assertFalse(ca.access.locked("builtin:default"));
        assertFalse(cb.access.locked("builtin:wine_fox/18_wedding"));
        assertTrue(cb.access.locked(alex), "the built-in wildcard is for built-in models only");
        quietly(() -> server.auth(b, "alex", true));
        deliver();
        assertFalse(cb.access.locked(alex), "a grant unlocks it too");
        assertTrue(
            server.accessReport(b)
                .contains("[allowed] alex - futura3d.playermodel.alex (granted)"));
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

    /** Exactly {@code n} ticks, delivering after each. */
    private void ticks(int n) {
        for (int t = 0; t < n; t++) {
            server.tick();
            deliver();
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
    void theChoiceIsRememberedAcrossRestartsOwnSkinIncluded() {
        permissionMode = true;
        ModelServer.Settings settings = new ModelServer.Settings();
        server = newServer(settings);
        server.loadNow();
        grantNode(a, "futura3d.playermodel.alex");
        Client ca = connect(a);
        run(200);
        String alex = server.snapshot()
            .byName("alex")
            .key();
        // the allowed model is chosen and remembered
        ca.send(new Message.Select(alex, null));
        deliver();
        assertEquals(alex, ca.keys.get(a));
        server.leave(a);
        clients.remove(a);

        ModelServer restarted = newServer(new ModelServer.Settings());
        restarted.loadNow();
        server = restarted;
        ca = connect(a);
        run(200);
        // the client's own saved choice (another model) does not override what the server remembers
        ca.send(new Message.Select("builtin:misc/2_steve", null, true));
        deliver();
        assertEquals(alex, ca.keys.get(a), "the server remembers the model");

        // now his own skin (Steve/Alex): remembered too, also against the client's saved local choice
        ca.send(new Message.Select(null, null));
        deliver();
        assertNull(ca.keys.get(a));
        server.leave(a);
        clients.remove(a);
        assertTrue(
            tmp.resolve("players/" + a + ".json")
                .toFile()
                .isFile(),
            "the own-skin choice is saved, not an empty record");
        server = newServer(new ModelServer.Settings());
        server.loadNow();
        ca = connect(a);
        run(200);
        ca.send(new Message.Select(alex, null, true));
        deliver();
        assertNull(ca.keys.get(a), "his own skin stays");
        assertTrue(ca.keys.containsKey(a));

        // a player the server never saw takes his client's saved choice (when allowed)
        Client cb = connect(b);
        cb.send(new Message.Select("builtin:misc/1_alex", null, true));
        deliver();
        assertEquals("builtin:misc/1_alex", cb.keys.get(b));
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

    private static Map<String, Float> vars(Object... kv) {
        Map<String, Float> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).floatValue());
        }
        return m;
    }

    @Test
    void roamingIsRelayedToTrackersAndKept() {
        Client ca = connect(a);
        Client cb = connect(b);
        trackers.put(a, new HashSet<>(Collections.singletonList(b)));
        ca.send(new Message.RoamingUpdate(true, vars("back", 1, "eye", 2)));
        deliver();
        assertEquals(vars("back", 1, "eye", 2), cb.roaming.get(a));
        assertNull(ca.roaming.get(a), "not echoed to the sender");
        ca.send(new Message.RoamingUpdate(false, vars("eye", 3)));
        deliver();
        assertEquals(vars("back", 1, "eye", 3), cb.roaming.get(a));
        assertEquals(vars("back", 1, "eye", 3), server.roamingOf(a));
        // a reset forgets the others
        ca.send(new Message.RoamingUpdate(true, vars("size", 0.5)));
        deliver();
        assertEquals(vars("size", 0.5), cb.roaming.get(a));
        assertEquals(vars("size", 0.5), server.roamingOf(a));
        // a player nobody tracks: kept, sent to nobody
        int before = cb.roamingLog.size();
        cb.send(new Message.RoamingUpdate(false, vars("x", 1)));
        deliver();
        assertEquals(vars("x", 1), server.roamingOf(b));
        assertEquals(before, cb.roamingLog.size());
        assertTrue(ca.roamingLog.isEmpty());
    }

    @Test
    void roamingLimitsAndRate() {
        Client ca = connect(a);
        Client cb = connect(b);
        trackers.put(a, new HashSet<>(Collections.singletonList(b)));
        // at most MAX_ROAMING_VARIABLES kept: 4 messages of 16, then one more name is dropped
        for (int m = 0; m < 4; m++) {
            Map<String, Float> part = new LinkedHashMap<>();
            for (int i = 0; i < Protocol.MAX_ROAMING_PER_MESSAGE; i++) {
                part.put("v" + (m * 16 + i), (float) i);
            }
            ca.send(new Message.RoamingUpdate(false, part));
        }
        ca.send(new Message.RoamingUpdate(false, vars("one_more", 1, "v0", 9)));
        deliver();
        assertEquals(
            Protocol.MAX_ROAMING_VARIABLES,
            server.roamingOf(a)
                .size());
        assertFalse(
            server.roamingOf(a)
                .containsKey("one_more"));
        assertEquals(
            9f,
            server.roamingOf(a)
                .get("v0"));
        assertEquals(
            Protocol.MAX_ROAMING_VARIABLES,
            cb.roaming.get(a)
                .size());
        // the rate limit: a burst of 20, then 10 a second
        Client cc = connect(vanilla);
        trackers.put(vanilla, new HashSet<>(Collections.singletonList(b)));
        int before = cb.roamingLog.size();
        for (int i = 0; i < 40; i++) {
            cc.send(new Message.RoamingUpdate(false, vars("n", i)));
        }
        deliver();
        assertEquals(20, cb.roamingLog.size() - before);
        assertEquals(
            19f,
            server.roamingOf(vanilla)
                .get("n"));
        for (int t = 0; t < 20; t++) {
            server.tick(); // one second: 10 more tokens
        }
        for (int i = 0; i < 40; i++) {
            cc.send(new Message.RoamingUpdate(false, vars("n", 100 + i)));
        }
        deliver();
        assertEquals(30, cb.roamingLog.size() - before);
    }

    @Test
    void roamingBelongsToTheModel() throws ModelServer.CommandError {
        Client ca = connect(a);
        connect(b);
        ca.send(new Message.RoamingUpdate(true, vars("back", 1)));
        deliver();
        assertEquals(vars("back", 1), server.roamingOf(a));
        server.set(a, "builtin:misc/3_default_boy", null);
        deliver();
        assertTrue(
            server.roamingOf(a)
                .isEmpty(),
            "a new model starts without the old one's variables");
        ca.send(new Message.RoamingUpdate(false, vars("back", 2)));
        deliver();
        server.set(a, "builtin:misc/3_default_boy", null); // the same model again: kept
        deliver();
        assertEquals(vars("back", 2), server.roamingOf(a));
    }

    @Test
    void syncGoesToTrackersAndBackToTheSender() {
        Client ca = connect(a);
        Client cb = connect(b);
        trackers.put(a, new HashSet<>(Collections.singletonList(b)));
        ca.send(new Message.SyncRequest(new float[] { 1, 2.5f }));
        deliver();
        assertEquals(Collections.singletonList("aaaa:[1.0, 2.5]"), cb.syncs);
        assertEquals(Collections.singletonList("aaaa:[1.0, 2.5]"), ca.syncs);
        for (int i = 0; i < 30; i++) {
            ca.send(new Message.SyncRequest(new float[] { i }));
        }
        deliver();
        assertEquals(1 + 19, cb.syncs.size(), "20 tokens at first");
    }

    @Test
    void startTrackingSendsTheEmoteAndTheRoaming() {
        Client ca = connect(a);
        Client cb = connect(b);
        // a plays an emote and sets 20 variables while nobody sees him
        ca.send(new Message.EmoteRequest("extra0"));
        Map<String, Float> part = new LinkedHashMap<>();
        for (int i = 0; i < Protocol.MAX_ROAMING_PER_MESSAGE; i++) {
            part.put("v" + i, (float) i);
        }
        ca.send(new Message.RoamingUpdate(true, part));
        ca.send(new Message.RoamingUpdate(false, vars("w", 1, "x", 2, "y", 3, "z", 4)));
        deliver();
        assertTrue(cb.emotes.isEmpty());
        assertEquals("extra0", server.emoteOf(a));
        // b comes close: the emote, then the variables in two messages (the first resetting)
        cb.roaming.put(a, vars("stale", 1));
        server.startTracking(b, a);
        deliver();
        assertEquals(Collections.singletonList("aaaa:extra0"), cb.emotes);
        assertEquals(
            20,
            cb.roaming.get(a)
                .size());
        assertFalse(
            cb.roaming.get(a)
                .containsKey("stale"));
        assertEquals(2, cb.roamingLog.size());
        assertTrue(
            cb.roamingLog.get(0)
                .startsWith("aaaa:reset:"));
        // the emote stopped (or ended by itself: the client says stop): nothing to send next time
        ca.send(new Message.EmoteRequest(null));
        deliver();
        assertNull(server.emoteOf(a));
        server.startTracking(b, a);
        deliver();
        assertEquals(1, cb.emotes.size());
        assertEquals(4, cb.roamingLog.size(), "the variables again: two messages for 20");
        // nothing for himself, nor for a client without the mod
        server.join(vanilla);
        deliver();
        int received = ca.received;
        server.startTracking(a, a);
        server.startTracking(vanilla, a);
        deliver();
        assertEquals(received, ca.received);
    }

    @Test
    void versionOneClientsGetNoRoaming() {
        Client cb = connect(b);
        // an old client (no fake session: what it is sent is recorded): version 1 hello
        server.join(a);
        trackers.put(b, new HashSet<>(Collections.singletonList(a)));
        server.received(a, new Message.ClientHello(1));
        assertTrue(server.hasMod(a));
        Message hello = toNobody.get(0);
        assertEquals(1, ((Message.ServerHello) hello).version, "answered in its version");
        toNobody.clear();
        cb.send(new Message.RoamingUpdate(true, vars("back", 1)));
        cb.send(new Message.SyncRequest(new float[] { 1 }));
        cb.send(new Message.EmoteRequest("extra0"));
        deliver();
        assertEquals(1, toNobody.size(), "only the emote reached the version-1 client");
        assertEquals(
            Protocol.S_EMOTE,
            toNobody.get(0)
                .type());
        // its own roaming and sync messages are ignored
        server.received(a, new Message.RoamingUpdate(true, vars("x", 1)));
        server.received(a, new Message.SyncRequest(new float[] { 1 }));
        assertTrue(
            server.roamingOf(a)
                .isEmpty());
        assertEquals(1, cb.syncs.size(), "only b's own, echoed");
        // start tracking: the emote only
        toNobody.clear();
        server.startTracking(a, b);
        assertEquals(1, toNobody.size());
        assertEquals(
            Protocol.S_EMOTE,
            toNobody.get(0)
                .type());
    }

    /**
     * A proxy server switch (Velocity/BungeeCord, lobby → this server): the client's first hello went to the lobby or
     * arrived before this server knew the player, and an operator ran /futura3d set before any handshake. The hello
     * the client repeats while unanswered brings the whole catalog, the legacy (memory-only) model included, its
     * files, and the chosen model; a further hello (our answer lost) is answered again, within its rate limit.
     */
    @Test
    void lateHelloAfterAProxySwitchGetsTheCatalogAndTheChosenModel() throws Exception {
        java.nio.file.Files.write(
            tmp.resolve("models/auth/hutao.ysm"),
            com.akashic.futura.model.legacy.LegacyTestSupport
                .bundle(2, com.akashic.futura.model.legacy.LegacyTestSupport.builtin("misc/3_default_boy"), 5));
        server.loadNow();
        Client ca = new Client(a);
        clients.put(a, ca);
        ca.session.start(); // to the lobby: nobody here knows the player yet
        deliver();
        assertTrue(ca.catalog.isEmpty());
        server.join(a);
        // the operator chooses before the client said anything that arrived
        assertEquals("hutao", server.set(a, "hutao", null));
        assertTrue(
            server.handshakeStatus(a)
                .startsWith("no futura3d handshake"));
        deliver();
        assertTrue(ca.catalog.isEmpty(), "nothing is sent to a client that has not said hello");

        assertTrue(ca.session.helloAgain());
        deliver();
        run(400);
        assertEquals("protocol " + Protocol.VERSION, server.handshakeStatus(a));
        CatalogEntry hutao = ca.entry("default boy");
        org.junit.jupiter.api.Assertions.assertNotNull(hutao, String.valueOf(ca.catalog));
        assertTrue(hutao.memoryOnly);
        assertTrue(ca.ready.containsKey(hutao.key()), "the legacy model's files arrived");
        assertEquals(hutao.key(), ca.keys.get(a), "and the client is told to use it");
        assertTrue(ca.session.memoryFiles() > 0);
        assertFalse(ca.session.helloAgain(), "connected: no more hellos");

        // our answer lost on the way (a new session on the client): the next hello is answered again
        Client again = new Client(a);
        clients.put(a, again);
        again.session.start();
        deliver();
        run(400);
        assertEquals(1, again.resets);
        assertEquals(hutao.key(), again.keys.get(a));
        // but not without limit
        int answered = 0;
        for (int i = 0; i < 10; i++) {
            Client spam = new Client(a);
            clients.put(a, spam);
            spam.session.start();
            deliver();
            answered += spam.resets;
        }
        assertTrue(answered < 10, "repeated hellos are rate limited: " + answered);
    }

    /** Thrown projectiles' owners go to version-3 clients only (older ones would refuse the type). */
    @Test
    void projectileOwnersOnlyToVersionThree() {
        server.join(a);
        server.received(a, new Message.ClientHello(2));
        toNobody.clear();
        server.projectileTracked(a, 42, b);
        assertTrue(toNobody.isEmpty(), "a version-2 client gets nothing");
        UUID c = new UUID(0, 333);
        server.join(c);
        server.received(c, new Message.ClientHello(Protocol.VERSION));
        toNobody.clear();
        server.projectileTracked(c, 42, b);
        assertEquals(1, toNobody.size());
        Message.ProjectileOwner owner = (Message.ProjectileOwner) toNobody.get(0);
        assertEquals(42, owner.entityId);
        assertEquals(b, owner.owner);
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

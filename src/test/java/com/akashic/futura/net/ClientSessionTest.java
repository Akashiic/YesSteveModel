package com.akashic.futura.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClientSessionTest {

    static final UUID SELF = new UUID(7, 7);

    /** Records what the session says. */
    static final class Recorder implements ClientSession.Listener, ClientSession.Output {

        final List<Message> sent = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<CatalogEntry> ready = new ArrayList<>();
        final List<UUID> players = new ArrayList<>();

        @Override
        public void send(Message m) {
            // through the codec, as on the wire
            try {
                sent.add(MessageCodec.decodeFromClient(MessageCodec.encode(m)));
            } catch (ProtocolException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void connected() {
            events.add("connected");
        }

        @Override
        public void incompatible(int v) {
            events.add("incompatible " + v);
        }

        @Override
        public void catalogReset(List<CatalogEntry> catalog) {
            events.add("reset " + catalog.size());
        }

        @Override
        public void modelReady(CatalogEntry e) {
            events.add("ready " + e.name);
            ready.add(e);
        }

        @Override
        public void modelFailed(CatalogEntry e, String reason) {
            events.add("failed " + e.name);
        }

        @Override
        public void state(UUID p, String key, String texture) {
            players.add(p);
            events.add("state " + key + " " + texture);
        }

        @Override
        public void emote(UUID p, String name) {
            players.add(p);
            events.add("emote " + name);
        }

        List<Sha256> requested() {
            List<Sha256> out = new ArrayList<>();
            for (Message m : sent) {
                if (m instanceof Message.RequestFiles) {
                    out.addAll(((Message.RequestFiles) m).hashes);
                }
            }
            return out;
        }
    }

    static Map<Sha256, byte[]> content = new HashMap<>();

    static FileRef file(String path, byte[] data) {
        Sha256 h = Sha256.of(data);
        content.put(h, data);
        return new FileRef(path, data.length, h);
    }

    static byte[] random(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    static CatalogEntry model(String name, FileRef... files) {
        List<FileRef> list = Arrays.asList(files);
        return new CatalogEntry(CatalogEntry.computeId(list), name, "", "", new ArrayList<>(), false, false, list);
    }

    /** Sends a whole file as chunks, as the server does. */
    static void sendFile(ClientSession s, Sha256 hash) {
        byte[] data = content.get(hash);
        int offset = 0;
        do {
            int len = Math.min(Protocol.CHUNK_DATA, data.length - offset);
            s.handle(new Message.FileChunk(hash, data.length, offset, Arrays.copyOfRange(data, offset, offset + len)));
            offset += len;
        } while (offset < data.length);
    }

    static void catalog(ClientSession s, int revision, CatalogEntry... entries) {
        s.handle(new Message.ServerHello(Protocol.VERSION, revision, entries.length, SELF));
        for (CatalogEntry e : entries) {
            s.handle(new Message.ModelEntry(revision, e));
        }
        s.handle(new Message.CatalogEnd(revision));
    }

    @Test
    void downloadsOnlyWhatTheCacheLacks() {
        MemoryFileCache cache = new MemoryFileCache();
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(cache, r, r);
        s.start();
        assertTrue(r.sent.get(0) instanceof Message.ClientHello);

        FileRef manifest = file("ysm.json", "{\"spec\":2}".getBytes());
        FileRef big = file("textures/big.png", random(100_000, 1));
        FileRef cached = file("models/main.json", "{}".getBytes());
        cache.store(cached.hash, content.get(cached.hash));
        CatalogEntry a = model("a", manifest, big, cached);

        catalog(s, 1, a);
        assertEquals(Arrays.asList("connected", "reset 1"), r.events);
        List<Sha256> asked = r.requested();
        assertEquals(2, asked.size());
        assertFalse(asked.contains(cached.hash));

        sendFile(s, manifest.hash);
        assertFalse(r.events.contains("ready a"));
        sendFile(s, big.hash);
        assertTrue(r.events.contains("ready a"));
        assertArrayEquals(content.get(big.hash), cache.files.get(big.hash));
        assertEquals(0, s.pendingFiles());
        assertEquals(100_000 + manifest.size, s.downloadedBytes());

        // a later catalog with the same model needs nothing new
        r.sent.clear();
        catalog(s, 2, a);
        assertTrue(
            r.requested()
                .isEmpty());
        assertEquals(2, r.ready.size());
    }

    @Test
    void requestsAFewFilesAtATime() {
        MemoryFileCache cache = new MemoryFileCache();
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(cache, r, r);
        List<FileRef> files = new ArrayList<>();
        files.add(file("ysm.json", "{}".getBytes()));
        for (int i = 0; i < 40; i++) {
            files.add(file("t" + i + ".png", random(1000, 100 + i)));
        }
        CatalogEntry big = model("many", files.toArray(new FileRef[0]));
        catalog(s, 1, big);
        assertEquals(
            ClientSession.MAX_INFLIGHT_FILES,
            r.requested()
                .size());
        // as files complete, more are asked for, until everything is stored
        int guard = 0;
        while (s.pendingFiles() > 0 && guard++ < 1000) {
            List<Sha256> asked = r.requested();
            r.sent.clear();
            for (Sha256 h : asked) {
                sendFile(s, h);
            }
        }
        assertTrue(r.events.contains("ready many"));
        assertEquals(41, cache.files.size());
    }

    @Test
    void refusesCorruptOrDisorderedFiles() {
        MemoryFileCache cache = new MemoryFileCache();
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(cache, r, r);
        FileRef manifest = file("ysm.json", "{}".getBytes());
        FileRef tex = file("a.png", random(70_000, 2));
        FileRef other = file("b.png", random(10, 3));
        CatalogEntry a = model("a", manifest, tex);
        CatalogEntry b = model("b", manifest, other);
        catalog(s, 1, a, b);

        // wrong bytes with the right size: the hash check fails and model a fails, b is unaffected
        byte[] wrong = random(70_000, 99);
        int offset = 0;
        while (offset < wrong.length) {
            int len = Math.min(Protocol.CHUNK_DATA, wrong.length - offset);
            s.handle(
                new Message.FileChunk(tex.hash, wrong.length, offset, Arrays.copyOfRange(wrong, offset, offset + len)));
            offset += len;
        }
        assertTrue(r.events.contains("failed a"));
        assertFalse(cache.files.containsKey(tex.hash));
        sendFile(s, manifest.hash);
        // a chunk out of order fails that file
        s.handle(new Message.FileChunk(other.hash, 10, 5, new byte[5]));
        assertTrue(r.events.contains("failed b"));
        assertFalse(r.events.contains("ready a"));
        assertFalse(r.events.contains("ready b"));
    }

    @Test
    void unavailableFilesFailTheirModels() {
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(new MemoryFileCache(), r, r);
        FileRef manifest = file("ysm.json", "{}".getBytes());
        CatalogEntry a = model("a", manifest);
        catalog(s, 1, a);
        s.handle(new Message.FileUnavailable(manifest.hash));
        assertTrue(r.events.contains("failed a"));
    }

    @Test
    void ignoresEverythingBeforeTheHelloAndAfterAMismatch() {
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(new MemoryFileCache(), r, r);
        UUID p = UUID.randomUUID();
        s.handle(new Message.PlayerStates(java.util.Collections.singletonList(new Message.PlayerState(p, null, null))));
        assertTrue(r.events.isEmpty());
        assertFalse(s.connected());

        s.handle(new Message.ServerHello(Protocol.VERSION + 1, 1, 0, SELF));
        assertEquals(Arrays.asList("incompatible " + (Protocol.VERSION + 1)), r.events);
        s.handle(new Message.ServerHello(Protocol.VERSION, 1, 0, SELF));
        s.handle(new Message.Emote(p, "x"));
        assertEquals(1, r.events.size());
    }

    @Test
    void passesStatesAndEmotesOn() {
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(new MemoryFileCache(), r, r);
        catalog(s, 1);
        UUID p = UUID.randomUUID();
        s.handle(
            new Message.PlayerStates(
                java.util.Collections.singletonList(new Message.PlayerState(p, "builtin:default", "blue"))));
        s.handle(new Message.Emote(p, "wave"));
        s.handle(new Message.Emote(p, null));
        assertEquals(
            Arrays.asList("connected", "reset 0", "state builtin:default blue", "emote wave", "emote null"),
            r.events);
    }

    @Test
    void mapsThePlayersServerUuidToTheLocalOne() {
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(new MemoryFileCache(), r, r);
        UUID local = UUID.randomUUID();
        s.setLocalPlayer(local);
        catalog(s, 1);
        UUID other = UUID.randomUUID();
        java.util.List<Message.PlayerState> states = new ArrayList<>();
        states.add(new Message.PlayerState(SELF, "builtin:default", null));
        states.add(new Message.PlayerState(other, null, null));
        s.handle(new Message.PlayerStates(states));
        s.handle(new Message.Emote(SELF, "wave"));
        assertEquals(Arrays.asList(local, other, local), r.players);
    }

    @Test
    void aNewCatalogReplacesTheOldOne() {
        Recorder r = new Recorder();
        ClientSession s = new ClientSession(new MemoryFileCache(), r, r);
        FileRef m1 = file("ysm.json", "{\"a\":1}".getBytes());
        FileRef m2 = file("ysm.json", "{\"a\":2}".getBytes());
        catalog(s, 1, model("one", m1));
        // entries of another revision are ignored; the end of revision 2 commits only its own entries
        s.handle(new Message.ServerHello(Protocol.VERSION, 2, 1, SELF));
        s.handle(new Message.ModelEntry(1, model("stale", m1)));
        s.handle(new Message.ModelEntry(2, model("two", m2)));
        s.handle(new Message.CatalogEnd(2));
        assertEquals(
            1,
            s.catalog()
                .size());
        assertEquals(
            "two",
            s.catalog()
                .get(0).name);
        // a chunk of a file from the old catalog is ignored
        sendFile(s, m1.hash);
        assertFalse(r.events.contains("ready one"));
        sendFile(s, m2.hash);
        assertTrue(r.events.contains("ready two"));
    }
}

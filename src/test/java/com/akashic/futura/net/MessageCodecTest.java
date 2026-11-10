package com.akashic.futura.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MessageCodecTest {

    static FileRef file(String path, String content) {
        byte[] b = content.getBytes(StandardCharsets.UTF_8);
        return new FileRef(path, b.length, Sha256.of(b));
    }

    static CatalogEntry entry(List<FileRef> files) {
        return new CatalogEntry(
            CatalogEntry.computeId(files),
            "Modelo ✓",
            "tips\nwith a line",
            "CC 0",
            Arrays.asList("a", "b"),
            true,
            false,
            files);
    }

    static List<FileRef> files() {
        return Arrays
            .asList(file("ysm.json", "{}"), file("models/main.json", "{\"a\":1}"), file("textures/a.png", "x"));
    }

    private static Message roundTrip(Message m) throws ProtocolException {
        byte[] bytes = MessageCodec.encode(m);
        assertTrue(bytes.length <= Protocol.TARGET_PAYLOAD);
        return m.fromServer() ? MessageCodec.decodeFromServer(bytes) : MessageCodec.decodeFromClient(bytes);
    }

    @Test
    void everyMessageRoundTrips() throws ProtocolException {
        Message.ServerHello hello = (Message.ServerHello) roundTrip(
            new Message.ServerHello(Protocol.VERSION, 7, 3, new UUID(1, 2)));
        assertEquals(Protocol.VERSION, hello.version);
        assertEquals(7, hello.revision);
        assertEquals(3, hello.modelCount);
        assertEquals(new UUID(1, 2), hello.self);

        CatalogEntry e = entry(files());
        Message.ModelEntry me = (Message.ModelEntry) roundTrip(new Message.ModelEntry(7, e.withLocked(true)));
        CatalogEntry memoryOnly = new CatalogEntry(
            e.id,
            e.name,
            e.tips,
            e.license,
            e.authors,
            false,
            false,
            true,
            e.files);
        Message.ModelEntry mo = (Message.ModelEntry) roundTrip(new Message.ModelEntry(8, memoryOnly.withLocked(true)));
        assertTrue(mo.entry.memoryOnly);
        assertTrue(mo.entry.locked);
        assertFalse(mo.entry.restricted);
        assertEquals(7, me.revision);
        assertEquals(e.id, me.entry.id);
        assertEquals("Modelo ✓", me.entry.name);
        assertEquals("tips\nwith a line", me.entry.tips);
        assertEquals("CC 0", me.entry.license);
        assertEquals(Arrays.asList("a", "b"), me.entry.authors);
        assertTrue(me.entry.restricted);
        assertTrue(me.entry.locked);
        assertEquals(e.files, me.entry.files);
        assertEquals(e.totalSize(), me.entry.totalSize());

        assertEquals(7, ((Message.CatalogEnd) roundTrip(new Message.CatalogEnd(7))).revision);

        byte[] data = new byte[Protocol.CHUNK_DATA];
        Arrays.fill(data, (byte) 9);
        Sha256 h = Sha256.of(new byte[] { 1 });
        Message.FileChunk c = (Message.FileChunk) roundTrip(new Message.FileChunk(h, 100000, 5000, data));
        assertEquals(h, c.hash);
        assertEquals(100000, c.total);
        assertEquals(5000, c.offset);
        assertArrayEquals(data, c.data);

        assertEquals(h, ((Message.FileUnavailable) roundTrip(new Message.FileUnavailable(h))).hash);

        UUID u = UUID.randomUUID();
        List<Message.PlayerState> states = new ArrayList<>();
        states.add(new Message.PlayerState(u, "builtin:wine_fox/18_wedding", "blue"));
        states.add(new Message.PlayerState(u, null, null));
        states.add(new Message.PlayerState(u, e.key(), null));
        Message.PlayerStates ps = (Message.PlayerStates) roundTrip(new Message.PlayerStates(states));
        assertEquals(3, ps.states.size());
        assertEquals(u, ps.states.get(0).player);
        assertEquals("builtin:wine_fox/18_wedding", ps.states.get(0).key);
        assertEquals("blue", ps.states.get(0).texture);
        assertNull(ps.states.get(1).key);
        assertNull(ps.states.get(1).texture);
        assertEquals(e.key(), ps.states.get(2).key);

        Message.Emote em = (Message.Emote) roundTrip(new Message.Emote(u, "extra0"));
        assertEquals(u, em.player);
        assertEquals("extra0", em.name);
        assertNull(((Message.Emote) roundTrip(new Message.Emote(u, null))).name);

        assertEquals(
            Protocol.VERSION,
            ((Message.ClientHello) roundTrip(new Message.ClientHello(Protocol.VERSION))).version);
        assertEquals(
            Collections.singletonList(h),
            ((Message.RequestFiles) roundTrip(new Message.RequestFiles(Collections.singletonList(h)))).hashes);
        Message.Select s = (Message.Select) roundTrip(new Message.Select("builtin:default", "default"));
        assertEquals("builtin:default", s.key);
        assertEquals("default", s.texture);
        assertFalse(s.initial);
        assertTrue(((Message.Select) roundTrip(new Message.Select("builtin:default", null, true))).initial);
        assertNull(((Message.Select) roundTrip(new Message.Select(null, null))).key);
        assertEquals("wave", ((Message.EmoteRequest) roundTrip(new Message.EmoteRequest("wave"))).name);
        assertNull(((Message.EmoteRequest) roundTrip(new Message.EmoteRequest(null))).name);
    }

    static Map<String, Float> vars(int n) {
        Map<String, Float> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            m.put("v" + i, i * 0.5f);
        }
        return m;
    }

    @Test
    void roamingAndSyncRoundTrip() throws ProtocolException {
        Map<String, Float> values = new LinkedHashMap<>();
        values.put("back", 1f);
        values.put("a.b_2", -3.25f);
        Message.Roaming r = (Message.Roaming) roundTrip(new Message.Roaming(new UUID(3, 4), true, values));
        assertEquals(new UUID(3, 4), r.player);
        assertTrue(r.reset);
        assertEquals(values, r.values);
        assertEquals(Arrays.asList("back", "a.b_2"), new ArrayList<>(r.values.keySet()), "order kept");
        Message.RoamingUpdate u = (Message.RoamingUpdate) roundTrip(new Message.RoamingUpdate(false, vars(16)));
        assertFalse(u.reset);
        assertEquals(vars(16), u.values);
        Message.ProjectileOwner po = (Message.ProjectileOwner) roundTrip(
            new Message.ProjectileOwner(-7, new UUID(8, 9)));
        assertEquals(-7, po.entityId);
        assertEquals(new UUID(8, 9), po.owner);
        Message.Sync sync = (Message.Sync) roundTrip(new Message.Sync(new UUID(5, 6), new float[] { 1, -2.5f, 0 }));
        assertArrayEquals(new float[] { 1, -2.5f, 0 }, sync.args);
        assertEquals(0, ((Message.SyncRequest) roundTrip(new Message.SyncRequest(new float[0]))).args.length);
        float[] sixteen = new float[Protocol.MAX_SYNC_ARGS];
        Arrays.fill(sixteen, 7);
        assertArrayEquals(sixteen, ((Message.SyncRequest) roundTrip(new Message.SyncRequest(sixteen))).args);
        // the sides
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromClient(MessageCodec.encode(new Message.Sync(new UUID(1, 1), new float[0]))));
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromServer(MessageCodec.encode(new Message.RoamingUpdate(true, vars(1)))));
    }

    @Test
    void roamingAndSyncLimits() throws IOException {
        // encoding refuses what decoding would
        assertThrows(
            IllegalArgumentException.class,
            () -> MessageCodec.encode(new Message.RoamingUpdate(false, vars(17))));
        Map<String, Float> bad = new LinkedHashMap<>();
        bad.put("Back", 1f);
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.encode(new Message.RoamingUpdate(false, bad)));
        Map<String, Float> nan = new LinkedHashMap<>();
        nan.put("x", Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> MessageCodec.encode(new Message.RoamingUpdate(false, nan)));
        assertThrows(
            IllegalArgumentException.class,
            () -> MessageCodec.encode(new Message.SyncRequest(new float[Protocol.MAX_SYNC_ARGS + 1])));
        assertThrows(
            IllegalArgumentException.class,
            () -> MessageCodec.encode(new Message.SyncRequest(new float[] { Float.POSITIVE_INFINITY })));

        // decoding, from hand-made payloads
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(roaming(17, "x", 1f)));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(roaming(1, "Bad", 1f)));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(roaming(1, "a..b", 1f)));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(roaming(1, ".a", 1f)));
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromClient(roaming(1, "abcdefghijklmnopqrstuvwxyz0123456", 1f)),
            "33 bytes");
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(roaming(1, "x", Float.NaN)));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(roaming(2, "x", 1f)), "repeated");
        assertEquals(1, ((Message.RoamingUpdate) assertDoesNotThrowDecode(roaming(1, "x", 1f))).values.size());
        // a bad reset flag
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Protocol.C_ROAMING);
        out.writeByte(2);
        out.writeByte(0);
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(bytes.toByteArray()));
        // sync: too many, non-finite, trailing
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(sync(17, 1f)));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(sync(1, Float.NEGATIVE_INFINITY)));
        byte[] ok = sync(2, 1f);
        byte[] trailing = Arrays.copyOf(ok, ok.length + 1);
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(trailing));
    }

    private static Message assertDoesNotThrowDecode(byte[] payload) {
        try {
            return MessageCodec.decodeFromClient(payload);
        } catch (ProtocolException e) {
            throw new AssertionError(e);
        }
    }

    /** A C_ROAMING payload with {@code n} entries all named {@code name}, valued {@code value}. */
    private static byte[] roaming(int n, String name, float value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Protocol.C_ROAMING);
        out.writeBoolean(false);
        out.writeByte(n);
        for (int i = 0; i < n; i++) {
            byte[] b = name.getBytes(StandardCharsets.UTF_8);
            out.writeShort(b.length);
            out.write(b);
            out.writeFloat(value);
        }
        return bytes.toByteArray();
    }

    private static byte[] sync(int n, float value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(Protocol.C_SYNC);
        out.writeByte(n);
        for (int i = 0; i < n; i++) {
            out.writeFloat(value);
        }
        return bytes.toByteArray();
    }

    @Test
    void roamingNames() {
        assertTrue(Protocol.validRoamingName("back"));
        assertTrue(Protocol.validRoamingName("a.b_2"));
        assertTrue(Protocol.validRoamingName("x"));
        assertFalse(Protocol.validRoamingName(""));
        assertFalse(Protocol.validRoamingName("a."));
        assertFalse(Protocol.validRoamingName("a b"));
        assertFalse(Protocol.validRoamingName("é"));
        assertFalse(Protocol.validRoamingName(null));
        assertTrue(Protocol.speaks(1));
        assertTrue(Protocol.speaks(Protocol.VERSION));
        assertFalse(Protocol.speaks(Protocol.VERSION + 1));
        assertFalse(Protocol.hasRoaming(1));
        assertTrue(Protocol.hasRoaming(2));
    }

    @Test
    void helloLayoutIsStable() {
        // type byte, then the version: any later protocol can read this much
        byte[] b = MessageCodec.encode(new Message.ClientHello(Protocol.VERSION));
        assertArrayEquals(new byte[] { (byte) Protocol.C_HELLO, 0, 0, 0, (byte) Protocol.VERSION }, b);
        byte[] s = MessageCodec.encode(new Message.ServerHello(Protocol.VERSION, 1, 0, new UUID(0, 0)));
        assertEquals(Protocol.S_HELLO, s[0]);
        assertEquals(Protocol.VERSION, s[4]);
    }

    @Test
    void refusesTheWrongSide() {
        byte[] fromClient = MessageCodec.encode(new Message.ClientHello(Protocol.VERSION));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(fromClient));
        byte[] fromServer = MessageCodec.encode(new Message.CatalogEnd(1));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(fromServer));
    }

    @Test
    void refusesMalformedPayloads() {
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(new byte[0]));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(new byte[] { 99 }));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(new byte[Protocol.MAX_PAYLOAD + 1]));
        // truncated
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(new byte[] { Protocol.S_HELLO, 0 }));
        // trailing bytes
        byte[] end = MessageCodec.encode(new Message.CatalogEnd(1));
        byte[] longer = Arrays.copyOf(end, end.length + 1);
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(longer));
    }

    /** Builds a payload by hand. */
    private interface Body {

        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] raw(int type, Body body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(type);
        body.write(out);
        return bytes.toByteArray();
    }

    private static void str(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.write(b);
    }

    @Test
    void enforcesCountsAndLengths() throws IOException {
        // too many models announced
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(raw(Protocol.S_HELLO, out -> {
            out.writeInt(Protocol.VERSION);
            out.writeInt(1);
            out.writeInt(Protocol.MAX_MODELS + 1);
        })));
        // a request for zero or too many files
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromClient(raw(Protocol.C_REQUEST, out -> out.writeShort(0))));
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec
                .decodeFromClient(raw(Protocol.C_REQUEST, out -> out.writeShort(Protocol.MAX_REQUEST + 1))));
        // a key that is too long, or not a key
        StringBuilder longKey = new StringBuilder("builtin:");
        while (longKey.length() <= Protocol.MAX_KEY_BYTES) {
            longKey.append('a');
        }
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(raw(Protocol.C_SELECT, out -> {
            out.writeBoolean(true);
            str(out, longKey.toString());
            out.writeBoolean(false);
        })));
        for (String bad : new String[] { "default", "builtin:../x", "builtin:A", "server:1234", "builtin:" }) {
            assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(raw(Protocol.C_SELECT, out -> {
                out.writeBoolean(true);
                str(out, bad);
                out.writeBoolean(false);
            })), bad);
        }
        // invalid UTF-8 and control characters in names
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(raw(Protocol.C_EMOTE, out -> {
            out.writeBoolean(true);
            out.writeShort(2);
            out.write(new byte[] { (byte) 0xC3, (byte) 0x28 });
        })));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromClient(raw(Protocol.C_EMOTE, out -> {
            out.writeBoolean(true);
            str(out, "a\u0000b");
        })));
        // a null flag other than 0 or 1
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromClient(raw(Protocol.C_EMOTE, out -> out.writeByte(2))));
        // a chunk past the end of its file, or bigger than a chunk
        Sha256 h = Sha256.of(new byte[0]);
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(raw(Protocol.S_CHUNK, out -> {
            out.write(h.bytes());
            out.writeInt(10);
            out.writeInt(8);
            out.writeInt(4);
            out.write(new byte[4]);
        })));
        assertThrows(ProtocolException.class, () -> MessageCodec.decodeFromServer(raw(Protocol.S_CHUNK, out -> {
            out.write(h.bytes());
            out.writeInt(Protocol.MAX_FILE_BYTES + 1);
            out.writeInt(0);
            out.writeInt(0);
        })));
        // too many states
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromServer(raw(Protocol.S_STATE, out -> out.writeByte(Protocol.MAX_STATES + 1))));
    }

    private static byte[] entryPayload(Sha256 id, List<FileRef> files) throws IOException {
        return raw(Protocol.S_MODEL, out -> {
            out.writeInt(1);
            out.write(id.bytes());
            str(out, "n");
            str(out, "");
            str(out, "");
            out.writeByte(0);
            out.writeByte(0);
            out.writeShort(files.size());
            for (FileRef f : files) {
                str(out, f.path);
                out.writeInt(f.size);
                out.write(f.hash.bytes());
            }
        });
    }

    @Test
    void checksCatalogEntries() throws IOException {
        List<FileRef> good = files();
        MessageCodec.decodeFromServer(entryPayload(CatalogEntry.computeId(good), good));

        // the id must be the hash of the file list
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromServer(entryPayload(Sha256.of(new byte[] { 1 }), good)));
        // paths that leave the model or are not clean
        for (String bad : new String[] { "../ysm.json", "/etc/passwd", "a/../../b", "./a.json", "a\\b.json", "a//b",
            "dir/" }) {
            List<FileRef> files = new ArrayList<>(good);
            files.add(file(bad, "x"));
            assertThrows(
                ProtocolException.class,
                () -> MessageCodec.decodeFromServer(entryPayload(CatalogEntry.computeId(files), files)),
                bad);
        }
        // repeated paths
        List<FileRef> twice = new ArrayList<>(good);
        twice.add(file("ysm.json", "other"));
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromServer(entryPayload(CatalogEntry.computeId(twice), twice)));
        // no manifest
        List<FileRef> noManifest = Collections.singletonList(file("a.json", "{}"));
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromServer(entryPayload(CatalogEntry.computeId(noManifest), noManifest)));
        // a model over the size limit
        List<FileRef> huge = new ArrayList<>(good);
        for (int i = 0; i < 5; i++) {
            huge.add(new FileRef("big" + i + ".png", Protocol.MAX_FILE_BYTES, Sha256.of(new byte[] { (byte) i })));
        }
        assertThrows(
            ProtocolException.class,
            () -> MessageCodec.decodeFromServer(entryPayload(CatalogEntry.computeId(huge), huge)));
    }

    @Test
    void idDependsOnEveryFileAndNotOnOrder() {
        List<FileRef> a = files();
        List<FileRef> reversed = new ArrayList<>(a);
        Collections.reverse(reversed);
        assertEquals(CatalogEntry.computeId(a), CatalogEntry.computeId(reversed));
        List<FileRef> changed = new ArrayList<>(a);
        changed.set(0, file("ysm.json", "{ }"));
        assertFalse(
            CatalogEntry.computeId(a)
                .equals(CatalogEntry.computeId(changed)));
    }

    @Test
    void keysAndTruncation() {
        assertTrue(Protocol.validKey("builtin:default"));
        assertTrue(Protocol.validKey("builtin:wine_fox/18_wedding"));
        assertTrue(
            Protocol.validKey(
                "server:" + Sha256.of(new byte[0])
                    .hex()));
        assertFalse(
            Protocol.validKey(
                "server:" + Sha256.of(new byte[0])
                    .hex()
                    .toUpperCase()));
        assertFalse(Protocol.validKey("builtin:a/../b"));
        assertEquals("abc", MessageCodec.truncate("abcdef", 3));
        assertEquals("a", MessageCodec.truncate("aé", 2)); // é is two bytes
        assertEquals("", MessageCodec.truncate(null, 3));
        assertEquals(
            Sha256.of(new byte[] { 5 }),
            Sha256.fromHex(
                Sha256.of(new byte[] { 5 })
                    .hex()));
    }
}

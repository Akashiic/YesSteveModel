package com.akashic.futura.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.akashic.futura.model.ModelSource;

/**
 * Encodes and decodes {@link Message}s. Every payload starts with the type byte; numbers are big endian
 * ({@code DataOutput}); a string is an unsigned 16-bit byte length and strict UTF-8; a nullable string is a byte (0 or
 * 1) and then the string; a hash is 32 raw bytes; a UUID is two longs.
 *
 * <pre>
 * S_HELLO        int version, int revision, int modelCount
 * S_MODEL        int revision, hash id, str name, str tips, str license, u8 n, n × str author,
 *                u8 flags (1 restricted, 2 locked), u16 n, n × (str path, int size, hash)
 * S_CATALOG_END  int revision
 * S_CHUNK        hash, int total, int offset, int length, length bytes
 * S_UNAVAILABLE  hash
 * S_STATE        u8 n, n × (uuid, nstr key, nstr texture)
 * S_EMOTE        uuid, nstr name (null: stop)
 * C_HELLO        int version
 * C_REQUEST      u16 n, n × hash
 * C_SELECT       nstr key (null: default model), nstr texture
 * C_EMOTE        nstr name (null: stop)
 * </pre>
 *
 * Decoding checks every limit of {@link Protocol} before allocating, rejects trailing bytes, messages from the wrong
 * side and unknown types, and checks a catalog entry's id against its files.
 */
public final class MessageCodec {

    private MessageCodec() {}

    // ---- Encoding ----

    /**
     * @throws IllegalArgumentException if a field is over its limit or the payload over {@link Protocol#MAX_PAYLOAD}
     */
    public static byte[] encode(Message m) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeByte(m.type());
            write(m, out);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e); // a ByteArrayOutputStream does not throw
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > Protocol.MAX_PAYLOAD) {
            throw new IllegalArgumentException("Message " + m.type() + " is " + payload.length + " bytes");
        }
        return payload;
    }

    private static void write(Message m, DataOutputStream out) throws IOException {
        switch (m.type()) {
            case Protocol.S_HELLO: {
                Message.ServerHello h = (Message.ServerHello) m;
                out.writeInt(h.version);
                out.writeInt(h.revision);
                out.writeInt(h.modelCount);
                break;
            }
            case Protocol.S_MODEL: {
                Message.ModelEntry e = (Message.ModelEntry) m;
                out.writeInt(e.revision);
                writeEntry(e.entry, out);
                break;
            }
            case Protocol.S_CATALOG_END:
                out.writeInt(((Message.CatalogEnd) m).revision);
                break;
            case Protocol.S_CHUNK: {
                Message.FileChunk c = (Message.FileChunk) m;
                check(c.data.length <= Protocol.CHUNK_DATA, "chunk too big");
                c.hash.writeTo(out);
                out.writeInt(c.total);
                out.writeInt(c.offset);
                out.writeInt(c.data.length);
                out.write(c.data);
                break;
            }
            case Protocol.S_UNAVAILABLE:
                ((Message.FileUnavailable) m).hash.writeTo(out);
                break;
            case Protocol.S_STATE: {
                List<Message.PlayerState> states = ((Message.PlayerStates) m).states;
                check(states.size() <= Protocol.MAX_STATES, "too many states");
                out.writeByte(states.size());
                for (Message.PlayerState s : states) {
                    writeUuid(s.player, out);
                    writeNullable(s.key, Protocol.MAX_KEY_BYTES, out);
                    writeNullable(s.texture, Protocol.MAX_TEXTURE_BYTES, out);
                }
                break;
            }
            case Protocol.S_EMOTE: {
                Message.Emote e = (Message.Emote) m;
                writeUuid(e.player, out);
                writeNullable(e.name, Protocol.MAX_ANIMATION_BYTES, out);
                break;
            }
            case Protocol.C_HELLO:
                out.writeInt(((Message.ClientHello) m).version);
                break;
            case Protocol.C_REQUEST: {
                List<Sha256> hashes = ((Message.RequestFiles) m).hashes;
                check(hashes.size() <= Protocol.MAX_REQUEST, "too many hashes");
                out.writeShort(hashes.size());
                for (Sha256 h : hashes) {
                    h.writeTo(out);
                }
                break;
            }
            case Protocol.C_SELECT: {
                Message.Select s = (Message.Select) m;
                writeNullable(s.key, Protocol.MAX_KEY_BYTES, out);
                writeNullable(s.texture, Protocol.MAX_TEXTURE_BYTES, out);
                break;
            }
            case Protocol.C_EMOTE:
                writeNullable(((Message.EmoteRequest) m).name, Protocol.MAX_ANIMATION_BYTES, out);
                break;
            default:
                throw new IllegalArgumentException("Unknown message type " + m.type());
        }
    }

    private static void writeEntry(CatalogEntry e, DataOutputStream out) throws IOException {
        e.id.writeTo(out);
        writeString(e.name, Protocol.MAX_NAME_BYTES, out);
        writeString(e.tips, Protocol.MAX_TIPS_BYTES, out);
        writeString(e.license, Protocol.MAX_LICENSE_BYTES, out);
        check(e.authors.size() <= Protocol.MAX_AUTHORS, "too many authors");
        out.writeByte(e.authors.size());
        for (String a : e.authors) {
            writeString(a, Protocol.MAX_AUTHOR_BYTES, out);
        }
        out.writeByte((e.restricted ? 1 : 0) | (e.locked ? 2 : 0));
        check(!e.files.isEmpty() && e.files.size() <= Protocol.MAX_FILES, "file count");
        out.writeShort(e.files.size());
        for (FileRef f : e.files) {
            writeString(f.path, Protocol.MAX_PATH_BYTES, out);
            out.writeInt(f.size);
            f.hash.writeTo(out);
        }
    }

    private static void writeUuid(UUID id, DataOutputStream out) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void writeString(String s, int maxBytes, DataOutputStream out) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        check(b.length <= maxBytes, "string over " + maxBytes + " bytes");
        out.writeShort(b.length);
        out.write(b);
    }

    private static void writeNullable(String s, int maxBytes, DataOutputStream out) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) {
            writeString(s, maxBytes, out);
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new IllegalArgumentException(what);
        }
    }

    /** Cuts a string to at most {@code maxBytes} of UTF-8 without splitting a character. */
    public static String truncate(String s, int maxBytes) {
        if (s == null) {
            return "";
        }
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) {
                break;
            }
            bytes += len;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    // ---- Decoding ----

    /** Decodes a payload the server sent. */
    public static Message decodeFromServer(byte[] payload) throws ProtocolException {
        Message m = decode(payload);
        if (!m.fromServer()) {
            throw new ProtocolException("Client message " + m.type() + " from the server");
        }
        return m;
    }

    /** Decodes a payload a client sent. */
    public static Message decodeFromClient(byte[] payload) throws ProtocolException {
        Message m = decode(payload);
        if (m.fromServer()) {
            throw new ProtocolException("Server message " + m.type() + " from a client");
        }
        return m;
    }

    static Message decode(byte[] payload) throws ProtocolException {
        if (payload.length == 0 || payload.length > Protocol.MAX_PAYLOAD) {
            throw new ProtocolException("Payload of " + payload.length + " bytes");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        try {
            Message m = read(in.readUnsignedByte(), in);
            if (in.available() > 0) {
                throw new ProtocolException("Trailing bytes after message " + m.type());
            }
            return m;
        } catch (EOFException e) {
            throw new ProtocolException("Truncated message");
        } catch (ProtocolException e) {
            throw e;
        } catch (IOException e) {
            throw new ProtocolException("Unreadable message: " + e.getMessage());
        }
    }

    private static Message read(int type, DataInputStream in) throws IOException {
        switch (type) {
            case Protocol.S_HELLO: {
                int version = in.readInt();
                int revision = in.readInt();
                int count = in.readInt();
                range(count, 0, Protocol.MAX_MODELS, "model count");
                return new Message.ServerHello(version, revision, count);
            }
            case Protocol.S_MODEL: {
                int revision = in.readInt();
                return new Message.ModelEntry(revision, readEntry(in));
            }
            case Protocol.S_CATALOG_END:
                return new Message.CatalogEnd(in.readInt());
            case Protocol.S_CHUNK: {
                Sha256 hash = readHash(in);
                int total = in.readInt();
                range(total, 0, Protocol.MAX_FILE_BYTES, "file size");
                int offset = in.readInt();
                range(offset, 0, total, "chunk offset");
                int length = in.readInt();
                range(length, 0, Math.min(Protocol.CHUNK_DATA, total - offset), "chunk length");
                byte[] data = new byte[length];
                in.readFully(data);
                return new Message.FileChunk(hash, total, offset, data);
            }
            case Protocol.S_UNAVAILABLE:
                return new Message.FileUnavailable(readHash(in));
            case Protocol.S_STATE: {
                int count = in.readUnsignedByte();
                range(count, 0, Protocol.MAX_STATES, "state count");
                List<Message.PlayerState> states = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    UUID player = readUuid(in);
                    String key = readKey(in);
                    String texture = readName(in, Protocol.MAX_TEXTURE_BYTES);
                    states.add(new Message.PlayerState(player, key, texture));
                }
                return new Message.PlayerStates(states);
            }
            case Protocol.S_EMOTE: {
                UUID player = readUuid(in);
                return new Message.Emote(player, readName(in, Protocol.MAX_ANIMATION_BYTES));
            }
            case Protocol.C_HELLO:
                return new Message.ClientHello(in.readInt());
            case Protocol.C_REQUEST: {
                int count = in.readUnsignedShort();
                range(count, 1, Protocol.MAX_REQUEST, "request count");
                List<Sha256> hashes = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    hashes.add(readHash(in));
                }
                return new Message.RequestFiles(hashes);
            }
            case Protocol.C_SELECT: {
                String key = readKey(in);
                return new Message.Select(key, readName(in, Protocol.MAX_TEXTURE_BYTES));
            }
            case Protocol.C_EMOTE:
                return new Message.EmoteRequest(readName(in, Protocol.MAX_ANIMATION_BYTES));
            default:
                throw new ProtocolException("Unknown message type " + type);
        }
    }

    private static CatalogEntry readEntry(DataInputStream in) throws IOException {
        Sha256 id = readHash(in);
        String name = printable(readString(in, Protocol.MAX_NAME_BYTES), "name");
        String tips = readString(in, Protocol.MAX_TIPS_BYTES);
        String license = printable(readString(in, Protocol.MAX_LICENSE_BYTES), "license");
        int authorCount = in.readUnsignedByte();
        range(authorCount, 0, Protocol.MAX_AUTHORS, "author count");
        List<String> authors = new ArrayList<>(authorCount);
        for (int i = 0; i < authorCount; i++) {
            authors.add(printable(readString(in, Protocol.MAX_AUTHOR_BYTES), "author"));
        }
        int flags = in.readUnsignedByte();
        range(flags, 0, 3, "flags");
        int fileCount = in.readUnsignedShort();
        range(fileCount, 1, Protocol.MAX_FILES, "file count");
        List<FileRef> files = new ArrayList<>(fileCount);
        Set<String> paths = new HashSet<>();
        long total = 0;
        for (int i = 0; i < fileCount; i++) {
            String path = readString(in, Protocol.MAX_PATH_BYTES);
            if (!validPath(path) || !paths.add(path)) {
                throw new ProtocolException("Bad or repeated path: " + path);
            }
            int size = in.readInt();
            range(size, 0, Protocol.MAX_FILE_BYTES, "file size");
            total += size;
            if (total > Protocol.MAX_MODEL_BYTES) {
                throw new ProtocolException("Model over " + Protocol.MAX_MODEL_BYTES + " bytes");
            }
            files.add(new FileRef(path, size, readHash(in)));
        }
        if (!paths.contains("ysm.json")) {
            throw new ProtocolException("Model without ysm.json");
        }
        if (!CatalogEntry.computeId(files)
            .equals(id)) {
            throw new ProtocolException("Model id does not match its files");
        }
        return new CatalogEntry(id, name, tips, license, authors, (flags & 1) != 0, (flags & 2) != 0, files);
    }

    /** A path as {@link ModelSource#clean} leaves it, without empty parts or control characters. */
    public static boolean validPath(String path) {
        if (path.isEmpty() || !Protocol.printable(path) || path.contains("//") || path.endsWith("/")) {
            return false;
        }
        try {
            return ModelSource.clean(path)
                .equals(path);
        } catch (IOException e) {
            return false;
        }
    }

    private static String readKey(DataInputStream in) throws IOException {
        String key = readNullable(in, Protocol.MAX_KEY_BYTES);
        if (key != null && !Protocol.validKey(key)) {
            throw new ProtocolException("Bad model key: " + key);
        }
        return key;
    }

    private static String readName(DataInputStream in, int maxBytes) throws IOException {
        String s = readNullable(in, maxBytes);
        return s == null ? null : printable(s, "name");
    }

    private static String printable(String s, String what) throws ProtocolException {
        if (!Protocol.printable(s)) {
            throw new ProtocolException("Control character in " + what);
        }
        return s;
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static Sha256 readHash(DataInputStream in) throws IOException {
        byte[] raw = new byte[Sha256.BYTES];
        in.readFully(raw);
        return Sha256.wrap(raw);
    }

    private static String readNullable(DataInputStream in, int maxBytes) throws IOException {
        int present = in.readUnsignedByte();
        range(present, 0, 1, "null flag");
        return present == 0 ? null : readString(in, maxBytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        range(length, 0, maxBytes, "string length");
        byte[] raw = new byte[length];
        in.readFully(raw);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("Invalid UTF-8");
        }
    }

    private static void range(long value, long min, long max, String what) throws ProtocolException {
        if (value < min || value > max) {
            throw new ProtocolException(what + " " + value + " outside " + min + ".." + max);
        }
    }
}

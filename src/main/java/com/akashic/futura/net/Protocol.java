package com.akashic.futura.net;

/**
 * The model protocol: one plugin-message channel, versioned messages in hand-written binary (see {@link MessageCodec}),
 * never Java serialization, never code. Every limit here is enforced when decoding.
 */
public final class Protocol {

    /** The channel: at most 16 characters, the limit of Bukkit 1.7's plugin messages (Thermos, Crucible). */
    public static final String CHANNEL = "futura3d:model";

    /**
     * Protocol version, sent in both hellos. The layout of the two hellos (type byte, then this int) never changes, so
     * any later version can recognise a mismatch. 2 adds roaming variables and {@code ysm.sync} events
     * ({@link #S_ROAMING}, {@link #S_SYNC}, {@link #C_ROAMING}, {@link #C_SYNC}) and the emote and roaming state sent
     * when a player starts tracking another.
     * <p>
     * Negotiation: the client says hello with its version; a server that speaks it (or any version from
     * {@link #MIN_VERSION} up) answers with that version and only sends what it has. A version-1 server answers a
     * version-2 hello with its own hello alone (version 1, no models), and the client says hello again with 1. Either
     * side never sends a message type the negotiated version lacks.
     */
    public static final int VERSION = 2;
    /** The oldest version this side still speaks. */
    public static final int MIN_VERSION = 1;
    /** The first version with roaming variables and {@code ysm.sync}. */
    public static final int ROAMING_VERSION = 2;

    /** Custom payloads in 1.7.10 cap at 32767 bytes (a client's {@code C17PacketCustomPayload} throws above it). */
    public static final int MAX_PAYLOAD = 32767;
    /** What an encoder may produce: every message stays below 30 KiB. */
    public static final int TARGET_PAYLOAD = 30 * 1024;
    /** File bytes per chunk: the chunk header (type, hash, sizes) fits in the rest of {@link #TARGET_PAYLOAD}. */
    public static final int CHUNK_DATA = TARGET_PAYLOAD - 64;

    // ---- Decoding limits ----

    /** Models in one catalog. */
    public static final int MAX_MODELS = 1024;
    /** Files in one model. */
    public static final int MAX_FILES = 512;
    /** UTF-8 bytes of a file path inside a model. */
    public static final int MAX_PATH_BYTES = 256;
    /** Size of one file. */
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    /** Sum of a model's files. */
    public static final long MAX_MODEL_BYTES = 64L * 1024 * 1024;
    /** Sum of every file a catalog asks the client to download. */
    public static final long MAX_CATALOG_BYTES = 1024L * 1024 * 1024;
    /** UTF-8 bytes of a model's display name. */
    public static final int MAX_NAME_BYTES = 256;
    /** UTF-8 bytes of a model's tips (description). */
    public static final int MAX_TIPS_BYTES = 2048;
    /** UTF-8 bytes of a licence name. */
    public static final int MAX_LICENSE_BYTES = 128;
    /** Authors listed in a catalog entry, and UTF-8 bytes of each name. */
    public static final int MAX_AUTHORS = 16;
    public static final int MAX_AUTHOR_BYTES = 128;
    /** UTF-8 bytes of a model key ({@code builtin:<folder>} or {@code server:<id>}). */
    public static final int MAX_KEY_BYTES = 128;
    /** UTF-8 bytes of a texture stem. */
    public static final int MAX_TEXTURE_BYTES = 128;
    /** UTF-8 bytes of an extra animation (emote) name. */
    public static final int MAX_ANIMATION_BYTES = 128;
    /** Hashes in one file request. */
    public static final int MAX_REQUEST = 256;
    /** Player states in one message. */
    public static final int MAX_STATES = 96;
    /** Roaming variables in one message (a change of more is split; a full state over several, the first resetting). */
    public static final int MAX_ROAMING_PER_MESSAGE = 16;
    /** Roaming variables kept per player (upstream {@code ProtocolLimits.MAX_ROAMING_VARIABLES}). */
    public static final int MAX_ROAMING_VARIABLES = 64;
    /** UTF-8 bytes of a roaming variable name, without {@code roaming.} (upstream's limit). */
    public static final int MAX_ROAMING_NAME_BYTES = 32;
    /** Arguments of one {@code ysm.sync} event (upstream {@code MolangEventCatalog.SYNC_MAX_ARGUMENTS}). */
    public static final int MAX_SYNC_ARGS = 16;

    // ---- Message types ----

    // Server to client
    public static final int S_HELLO = 1;
    public static final int S_MODEL = 2;
    public static final int S_CATALOG_END = 3;
    public static final int S_CHUNK = 4;
    public static final int S_UNAVAILABLE = 5;
    public static final int S_STATE = 6;
    public static final int S_EMOTE = 7;
    /** Version 2: a player's roaming variables changed (or all of them, to someone who starts seeing him). */
    public static final int S_ROAMING = 8;
    /** Version 2: a player's model ran {@code ysm.sync(...)}. */
    public static final int S_SYNC = 9;

    // Client to server
    public static final int C_HELLO = 64;
    public static final int C_REQUEST = 65;
    public static final int C_SELECT = 66;
    public static final int C_EMOTE = 67;
    /** Version 2: the local player's roaming variables changed. */
    public static final int C_ROAMING = 68;
    /** Version 2: the local player's model ran {@code ysm.sync(...)}. */
    public static final int C_SYNC = 69;

    /** Prefix of built-in model keys (the client has them; never transferred). */
    public static final String BUILTIN = "builtin:";
    /** Prefix of server model keys, followed by the model id in lowercase hex. */
    public static final String SERVER = "server:";

    private Protocol() {}

    /** Whether a key has a valid shape: {@code builtin:<folder>} or {@code server:<64 hex digits>}. */
    public static boolean validKey(String key) {
        if (key == null) {
            return false;
        }
        if (key.startsWith(SERVER)) {
            return Sha256.isHex(key.substring(SERVER.length()));
        }
        return key.startsWith(BUILTIN) && validBuiltin(key.substring(BUILTIN.length()));
    }

    /** A built-in folder: lowercase letters, digits, {@code _ - .} and {@code /} between parts, no {@code ..}. */
    public static boolean validBuiltin(String folder) {
        if (folder.isEmpty() || folder.length() > 96 || folder.startsWith("/") || folder.endsWith("/")) {
            return false;
        }
        for (String part : folder.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        for (int i = 0; i < folder.length(); i++) {
            char c = folder.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || c == '/';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Whether the version has roaming variables and {@code ysm.sync}. */
    public static boolean hasRoaming(int version) {
        return version >= ROAMING_VERSION;
    }

    /** Whether this side speaks the version. */
    public static boolean speaks(int version) {
        return version >= MIN_VERSION && version <= VERSION;
    }

    /**
     * A roaming variable name as Molang stores it, without {@code roaming.}: 1 to {@link #MAX_ROAMING_NAME_BYTES}
     * lower-case letters, digits and {@code _}, in parts joined by single dots ({@code v.roaming.a.b} is
     * {@code "a.b"}).
     */
    public static boolean validRoamingName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_ROAMING_NAME_BYTES) {
            return false;
        }
        char previous = '.';
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                if (previous == '.') {
                    return false;
                }
            } else if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
            previous = c;
        }
        return previous != '.';
    }

    /** Text sent for display or as a name: no control characters. */
    public static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

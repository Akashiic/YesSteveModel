package com.akashic.futura.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A protocol message. The wire layout of each is in {@link MessageCodec}. */
public abstract class Message {

    private Message() {}

    /** The type byte ({@code Protocol.S_*} or {@code Protocol.C_*}). */
    public abstract int type();

    /** Whether the server sends it (otherwise the client does). */
    public final boolean fromServer() {
        return type() < Protocol.C_HELLO;
    }

    // ---- Server to client ----

    /** Starts a catalog: the server speaks this protocol; {@code modelCount} {@link ModelEntry}s and an end follow. */
    public static final class ServerHello extends Message {

        public final int version;
        /** Changes on every reload; entries and the end carry it. */
        public final int revision;
        public final int modelCount;
        /**
         * The receiving player's UUID on the server. In offline mode (common on Thermos) it differs from the UUID of
         * the client's own session, so the client maps states and emotes about this UUID to its local player.
         */
        public final UUID self;

        public ServerHello(int version, int revision, int modelCount, UUID self) {
            this.version = version;
            this.revision = revision;
            this.modelCount = modelCount;
            this.self = self;
        }

        @Override
        public int type() {
            return Protocol.S_HELLO;
        }
    }

    /** One model of the catalog. */
    public static final class ModelEntry extends Message {

        public final int revision;
        public final CatalogEntry entry;

        public ModelEntry(int revision, CatalogEntry entry) {
            this.revision = revision;
            this.entry = entry;
        }

        @Override
        public int type() {
            return Protocol.S_MODEL;
        }
    }

    /** The catalog is complete: it replaces the previous one. */
    public static final class CatalogEnd extends Message {

        public final int revision;

        public CatalogEnd(int revision) {
            this.revision = revision;
        }

        @Override
        public int type() {
            return Protocol.S_CATALOG_END;
        }
    }

    /** A piece of a requested file, sent in order from offset 0. */
    public static final class FileChunk extends Message {

        public final Sha256 hash;
        /** The whole file's size. */
        public final int total;
        public final int offset;
        public final byte[] data;

        public FileChunk(Sha256 hash, int total, int offset, byte[] data) {
            this.hash = hash;
            this.total = total;
            this.offset = offset;
            this.data = data;
        }

        @Override
        public int type() {
            return Protocol.S_CHUNK;
        }
    }

    /** The server cannot send a requested file (not in its catalog any more). */
    public static final class FileUnavailable extends Message {

        public final Sha256 hash;

        public FileUnavailable(Sha256 hash) {
            this.hash = hash;
        }

        @Override
        public int type() {
            return Protocol.S_UNAVAILABLE;
        }
    }

    /** Which model a player uses. */
    public static final class PlayerState {

        public final UUID player;
        /** {@code builtin:<folder>} or {@code server:<id>}, or null for the default model. */
        public final String key;
        /** Texture stem, or null for the model's default texture. */
        public final String texture;

        public PlayerState(UUID player, String key, String texture) {
            this.player = player;
            this.key = key;
            this.texture = texture;
        }
    }

    /** Player states (at most {@link Protocol#MAX_STATES} per message). */
    public static final class PlayerStates extends Message {

        public final List<PlayerState> states;

        public PlayerStates(List<PlayerState> states) {
            this.states = Collections.unmodifiableList(new ArrayList<>(states));
        }

        @Override
        public int type() {
            return Protocol.S_STATE;
        }
    }

    /** A player started an extra animation, or stopped it ({@code name} null). */
    /** Version 3: the player who threw a projectile ({@link Protocol#S_PROJECTILE_OWNER}). */
    public static final class ProjectileOwner extends Message {

        public final int entityId;
        public final UUID owner;

        public ProjectileOwner(int entityId, UUID owner) {
            this.entityId = entityId;
            this.owner = owner;
        }

        @Override
        public int type() {
            return Protocol.S_PROJECTILE_OWNER;
        }
    }

    /** Version 4: which models the player may select ({@link Protocol#S_ACCESS}). */
    public static final class Access extends Message {

        /** The first message of a list: forget the previous one. */
        public final boolean reset;
        /** True: the keys are the selectable models, all others are locked; false: the keys are the locked ones. */
        public final boolean listsUnlocked;
        public final List<String> keys;

        public Access(boolean reset, boolean listsUnlocked, List<String> keys) {
            this.reset = reset;
            this.listsUnlocked = listsUnlocked;
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        }

        @Override
        public int type() {
            return Protocol.S_ACCESS;
        }
    }

    public static final class Emote extends Message {

        public final UUID player;
        public final String name;

        public Emote(UUID player, String name) {
            this.player = player;
            this.name = name;
        }

        @Override
        public int type() {
            return Protocol.S_EMOTE;
        }
    }

    /**
     * Version 2: a player's roaming variables ({@code v.roaming.*}). {@code reset}: forget his other variables first
     * (a full state, sent to someone who starts seeing him; a full state of more than
     * {@link Protocol#MAX_ROAMING_PER_MESSAGE} goes in several messages, only the first resetting).
     */
    public static final class Roaming extends Message {

        public final UUID player;
        public final boolean reset;
        /** Name without {@code roaming.} to a finite value, in the order sent. */
        public final Map<String, Float> values;

        public Roaming(UUID player, boolean reset, Map<String, Float> values) {
            this.player = player;
            this.reset = reset;
            this.values = copy(values);
        }

        @Override
        public int type() {
            return Protocol.S_ROAMING;
        }
    }

    /** Version 2: a player's model ran {@code ysm.sync(args)}; the receiver runs the model's {@code sync} handlers. */
    public static final class Sync extends Message {

        public final UUID player;
        public final float[] args;

        public Sync(UUID player, float[] args) {
            this.player = player;
            this.args = args.clone();
        }

        @Override
        public int type() {
            return Protocol.S_SYNC;
        }
    }

    static Map<String, Float> copy(Map<String, Float> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    // ---- Client to server ----

    /** The client runs the mod and speaks this protocol: the server answers with the catalog and the states. */
    public static final class ClientHello extends Message {

        public final int version;

        public ClientHello(int version) {
            this.version = version;
        }

        @Override
        public int type() {
            return Protocol.C_HELLO;
        }
    }

    /** Files the client does not have in its cache. */
    public static final class RequestFiles extends Message {

        public final List<Sha256> hashes;

        public RequestFiles(List<Sha256> hashes) {
            this.hashes = Collections.unmodifiableList(new ArrayList<>(hashes));
        }

        @Override
        public int type() {
            return Protocol.C_REQUEST;
        }
    }

    /**
     * The local player chose a model in the picker (the server validates it and answers with the state), or, with
     * {@code initial}, the client's saved local choice sent on joining: used only if the server has nothing saved for
     * the player.
     */
    public static final class Select extends Message {

        /** A model key, or null for the default model. */
        public final String key;
        public final String texture;
        public final boolean initial;

        public Select(String key, String texture) {
            this(key, texture, false);
        }

        public Select(String key, String texture, boolean initial) {
            this.key = key;
            this.texture = texture;
            this.initial = initial;
        }

        @Override
        public int type() {
            return Protocol.C_SELECT;
        }
    }

    /** The local player started an extra animation, or stopped it ({@code name} null). */
    public static final class EmoteRequest extends Message {

        public final String name;

        public EmoteRequest(String name) {
            this.name = name;
        }

        @Override
        public int type() {
            return Protocol.C_EMOTE;
        }
    }

    /** Version 2: the local player's roaming variables changed ({@code reset}: forget the others first). */
    public static final class RoamingUpdate extends Message {

        public final boolean reset;
        public final Map<String, Float> values;

        public RoamingUpdate(boolean reset, Map<String, Float> values) {
            this.reset = reset;
            this.values = copy(values);
        }

        @Override
        public int type() {
            return Protocol.C_ROAMING;
        }
    }

    /** Version 2: the local player's model ran {@code ysm.sync(args)}. */
    public static final class SyncRequest extends Message {

        public final float[] args;

        public SyncRequest(float[] args) {
            this.args = args.clone();
        }

        @Override
        public int type() {
            return Protocol.C_SYNC;
        }
    }
}

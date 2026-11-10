package com.akashic.futura.anim;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * States other mods set on a player for the animation runtime ({@code FuturaModels.setExternalState}), by player
 * UUID, without Minecraft: what upstream reads from the mods themselves (SlashBlade's blade state), which on 1.7.10
 * comes from the mod that has it (Kunftig's SlashBlade-style blades). A key's {@link Entry#generation} changes on
 * every change of its value, so the same value set again after a clear is seen as new (a combo started again).
 * Bounded: keys of at most {@link #MAX_KEY} characters, values of at most {@link #MAX_VALUE}, {@link #MAX_KEYS} per
 * player, {@link #MAX_PLAYERS} players (the least recently set are dropped). One thread (the client's).
 */
public final class ExternalStates {

    public static final int MAX_KEY = 64;
    public static final int MAX_VALUE = 128;
    public static final int MAX_KEYS = 16;
    public static final int MAX_PLAYERS = 512;

    /** One player's states. */
    public static final class Entry {

        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Integer> generations = new HashMap<>();

        /** The value, or null when unset. */
        public String get(String key) {
            return values.get(key);
        }

        /** Changes on every change of the key's value (0 before the first). */
        public int generation(String key) {
            Integer g = generations.get(key);
            return g == null ? 0 : g;
        }

        boolean set(String key, String value) {
            String before = values.get(key);
            if (value == null ? before == null : value.equals(before)) {
                return true;
            }
            if (value != null && before == null && values.size() >= MAX_KEYS) {
                return false;
            }
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
            generations.put(key, generation(key) + 1);
            return true;
        }

        boolean isEmpty() {
            return values.isEmpty();
        }
    }

    private final Map<UUID, Entry> players = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Sets or clears ({@code value} null) a state.
     *
     * @return false when the key or value is over its limit, or the player has too many keys (nothing changes)
     */
    public boolean set(UUID player, String key, String value) {
        if (player == null || key == null || key.isEmpty() || key.length() > MAX_KEY) {
            return false;
        }
        if (value != null && value.length() > MAX_VALUE) {
            return false;
        }
        Entry e = players.get(player);
        if (e == null) {
            if (value == null) {
                return true;
            }
            if (players.size() >= MAX_PLAYERS) {
                players.remove(
                    players.keySet()
                        .iterator()
                        .next());
            }
            e = new Entry();
            players.put(player, e);
        }
        return e.set(key, value);
    }

    /** A player's states, or null when he has none. */
    public Entry of(UUID player) {
        return players.get(player);
    }

    public String get(UUID player, String key) {
        Entry e = players.get(player);
        return e == null ? null : e.get(key);
    }

    /** Forgets everything (leaving a world). */
    public void clear() {
        players.clear();
    }

    public int players() {
        return players.size();
    }
}

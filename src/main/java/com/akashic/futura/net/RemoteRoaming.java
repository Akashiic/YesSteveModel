package com.akashic.futura.net;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The roaming variables of the other players as the server relayed them, without Minecraft: kept per player so they
 * survive the player's animator being replaced (his model finished loading, he came back into view) and are written
 * into each new one. A player's variables are forgotten when his model changes. At most
 * {@link Protocol#MAX_ROAMING_VARIABLES} per player and {@link #MAX_PLAYERS} players. One thread (the client's).
 */
public final class RemoteRoaming {

    /** Players remembered; past it the oldest-touched are not tracked (the server only sends what one sees). */
    public static final int MAX_PLAYERS = 1024;

    private final Map<UUID, Map<String, Float>> values = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<UUID, Integer> versions = new HashMap<>();

    /** A relayed update; values must be valid already (the codec checked them). */
    public void apply(UUID player, boolean reset, Map<String, Float> update) {
        Map<String, Float> mine = values.get(player);
        if (mine == null) {
            if (values.size() >= MAX_PLAYERS) {
                UUID eldest = values.keySet()
                    .iterator()
                    .next();
                values.remove(eldest);
                versions.remove(eldest);
            }
            mine = new LinkedHashMap<>();
            values.put(player, mine);
        }
        if (reset) {
            mine.clear();
        }
        for (Map.Entry<String, Float> e : update.entrySet()) {
            if (mine.containsKey(e.getKey()) || mine.size() < Protocol.MAX_ROAMING_VARIABLES) {
                mine.put(e.getKey(), e.getValue());
            }
        }
        Integer v = versions.get(player);
        versions.put(player, v == null ? 1 : v + 1);
    }

    /** His model changed: his variables belong to the old one. */
    public void forget(UUID player) {
        if (values.remove(player) != null) {
            Integer v = versions.get(player);
            versions.put(player, v == null ? 1 : v + 1);
        }
    }

    public void clear() {
        values.clear();
        versions.clear();
    }

    /** A player's variables (unmodifiable; empty when none). */
    public Map<String, Float> of(UUID player) {
        Map<String, Float> mine = values.get(player);
        return mine == null ? Collections.<String, Float>emptyMap() : Collections.unmodifiableMap(mine);
    }

    /** Changes on every update of the player: an animator written at another version needs writing again. */
    public int version(UUID player) {
        Integer v = versions.get(player);
        return v == null ? 0 : v;
    }

    public int players() {
        return values.size();
    }
}

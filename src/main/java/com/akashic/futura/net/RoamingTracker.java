package com.akashic.futura.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The local player's side of roaming variables ({@code v.roaming.*}): compares what the model holds now with what
 * the server was last told and gives the messages that bring it up to date, without Minecraft. Only numbers travel;
 * names that are not {@link Protocol#validRoamingName valid}, non-finite values and variables past
 * {@link Protocol#MAX_ROAMING_VARIABLES} stay local. Changes are sent at most every {@link #MIN_INTERVAL} ticks
 * (a dragged slider sends the last value, not every step). One thread (the client's).
 */
public final class RoamingTracker {

    /** Ticks between two sends: at most 5 a second, under the server's 10 a second. */
    public static final int MIN_INTERVAL = 4;

    private final Map<String, Float> sent = new LinkedHashMap<>();
    private boolean reset = true;
    private long lastSend = Long.MIN_VALUE / 2;

    /** The next poll sends everything, resetting (a new model or a new connection). */
    public void reset() {
        sent.clear();
        reset = true;
    }

    /** What the server was last told (a copy). */
    public Map<String, Float> sent() {
        return new LinkedHashMap<>(sent);
    }

    /**
     * @param current the model's roaming variables now (name without {@code roaming.} to value; anything else in it
     *                is ignored)
     * @param tick    a clock in ticks
     * @return the messages to send now, in order (empty: nothing changed, or too soon after the last send)
     */
    public List<Message.RoamingUpdate> poll(Map<String, Float> current, long tick) {
        Map<String, Float> wanted = filter(current);
        Map<String, Float> changes = new LinkedHashMap<>();
        boolean removed = false;
        for (Map.Entry<String, Float> e : wanted.entrySet()) {
            Float before = sent.get(e.getKey());
            if (before == null || Float.floatToIntBits(before) != Float.floatToIntBits(e.getValue())) {
                changes.put(e.getKey(), e.getValue());
            }
        }
        for (String name : sent.keySet()) {
            if (!wanted.containsKey(name)) {
                removed = true; // a variable made missing again: only a reset can say so
                break;
            }
        }
        boolean full = reset || removed;
        if (!full && changes.isEmpty()) {
            return Collections.emptyList();
        }
        if (tick - lastSend < MIN_INTERVAL) {
            return Collections.emptyList();
        }
        lastSend = tick;
        List<Message.RoamingUpdate> out = new ArrayList<>();
        Map<String, Float> source = full ? wanted : changes;
        List<Map.Entry<String, Float>> entries = new ArrayList<>(source.entrySet());
        int i = 0;
        boolean first = true;
        do {
            Map<String, Float> part = new LinkedHashMap<>();
            for (int n = 0; n < Protocol.MAX_ROAMING_PER_MESSAGE && i < entries.size(); n++, i++) {
                part.put(
                    entries.get(i)
                        .getKey(),
                    entries.get(i)
                        .getValue());
            }
            out.add(new Message.RoamingUpdate(full && first, part));
            first = false;
        } while (i < entries.size());
        if (full) {
            sent.clear();
        }
        sent.putAll(source);
        reset = false;
        return out;
    }

    /**
     * The variables that may travel: valid names, finite values, the first {@link Protocol#MAX_ROAMING_VARIABLES} by
     * name.
     */
    static Map<String, Float> filter(Map<String, Float> current) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (Map.Entry<String, Float> e : new TreeMap<>(current).entrySet()) {
            Float v = e.getValue();
            if (v != null && MessageCodec.finite(v)
                && Protocol.validRoamingName(e.getKey())
                && out.size() < Protocol.MAX_ROAMING_VARIABLES) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}

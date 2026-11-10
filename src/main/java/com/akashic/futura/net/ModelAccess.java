package com.akashic.futura.net;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The client's view of which models the local player may select, from the server's {@link Protocol#S_ACCESS} messages
 * (protocol 4). Unknown (no server, or an older one): nothing is locked here. Choosing no model (the player's own
 * skin) is never locked. Not thread-safe.
 */
public final class ModelAccess {

    private boolean known;
    private boolean listsUnlocked;
    private final Set<String> keys = new HashSet<>();

    /** One {@link Message.Access}: the first of a list ({@code reset}) replaces what was known. */
    public void apply(boolean reset, boolean listsUnlocked, List<String> keys) {
        if (reset || !known || this.listsUnlocked != listsUnlocked) {
            this.keys.clear();
            this.listsUnlocked = listsUnlocked;
            known = true;
        }
        this.keys.addAll(keys);
    }

    /** Whether the server said the local player may not select this model. */
    public boolean locked(String key) {
        if (!known || key == null) {
            return false;
        }
        return listsUnlocked != keys.contains(key);
    }

    public boolean known() {
        return known;
    }

    /** Forgets everything (disconnected). */
    public void clear() {
        known = false;
        keys.clear();
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/sound/instance/SoundInstanceManager.java and
 * src/main/java/com/elfmcys/ysm/expression/host/ModelSoundId.java, Apache License 2.0.
 * Changes: no Minecraft types; ids are an Integer (numeric) or a String key; playback goes through a SoundBackend.
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The sounds one author scope started: an animation's timeline, a Bedrock controller, or the player's global scope.
 * Ids: numeric (an {@link Integer}; 0 is anonymous, negative never plays) and string ids never alias, so the string
 * {@code "0"} is an ordinary id. Playing an id that is still playing is refused unless forced (the old one is then
 * stopped); stopping a scope stops everything it started.
 */
public final class SoundScope {

    private final Map<Object, SoundBackend.SoundHandle> named = new HashMap<>();
    private final List<SoundBackend.SoundHandle> unnamed = new ArrayList<>();

    /** The id of a Molang argument: a number becomes an {@link Integer} (truncated), anything else its string. */
    public static Object id(com.akashic.futura.molang.MolangValue value) {
        if (value == null) {
            return 0;
        }
        if (value.kind == com.akashic.futura.molang.MolangValue.Kind.STRING) {
            return value.asString();
        }
        return (int) value.asNumber();
    }

    static boolean playable(Object id) {
        return !(id instanceof Integer) || (Integer) id >= 0;
    }

    private static boolean isUnnamed(Object id) {
        return id instanceof Integer && (Integer) id == 0;
    }

    /**
     * Starts a sound under an id.
     *
     * @return true if it started
     */
    public boolean play(SoundBackend backend, Object id, String sound, boolean force, float volume, float pitch,
        boolean loop) {
        if (backend == null || !playable(id)
            || sound == null
            || sound.trim()
                .isEmpty()) {
            return false;
        }
        if (!isUnnamed(id)) {
            SoundBackend.SoundHandle old = named.get(id);
            if (old != null && !old.isStopped()) {
                if (!force) {
                    return false;
                }
                named.remove(id);
                old.stop();
            }
        }
        SoundBackend.SoundHandle handle;
        try {
            handle = backend.play(sound, volume, pitch, loop);
        } catch (RuntimeException e) {
            return false;
        }
        if (handle == null || handle.isStopped()) {
            return false;
        }
        if (isUnnamed(id)) {
            unnamed.add(handle);
        } else {
            named.put(id, handle);
        }
        return true;
    }

    /** Stops the sound of a (named) id. */
    public boolean stop(Object id) {
        if (!playable(id) || isUnnamed(id)) {
            return false;
        }
        SoundBackend.SoundHandle handle = named.remove(id);
        if (handle == null) {
            return false;
        }
        handle.stop();
        return true;
    }

    /** Stops everything this scope started. */
    public void stopAll() {
        if (named.isEmpty() && unnamed.isEmpty()) {
            return;
        }
        for (SoundBackend.SoundHandle handle : named.values()) {
            handle.stop();
        }
        for (SoundBackend.SoundHandle handle : unnamed) {
            handle.stop();
        }
        named.clear();
        unnamed.clear();
    }

    /** Forgets the sounds that ended. */
    public void trim() {
        for (Iterator<SoundBackend.SoundHandle> it = named.values()
            .iterator(); it.hasNext();) {
            if (it.next()
                .isStopped()) {
                it.remove();
            }
        }
        unnamed.removeIf(SoundBackend.SoundHandle::isStopped);
    }

    /** Sounds started and not known to have ended. */
    public int size() {
        return named.size() + unnamed.size();
    }
}

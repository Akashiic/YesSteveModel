package com.akashic.futura.molang;

import java.util.HashMap;
import java.util.Map;

/**
 * Named Molang variables ({@code v.name}, also dotted struct paths such as {@code v.a.b}, stored by full path).
 * <p>
 * Keys have no namespace prefix and are lower-case: {@code v.Speed} and {@code variable.speed} are {@code "speed"},
 * {@code v.a.b} is {@code "a.b"}. Roaming variables ({@code v.roaming.x}, {@code roaming.x}, {@code r.x}) are
 * {@code "roaming.x"}. Not thread-safe: one instance per entity.
 */
public final class MolangVariables {

    private final Map<String, MolangValue> values = new HashMap<>();

    /** @return the value, or {@link MolangValue#ZERO} if never set */
    public MolangValue get(String name) {
        MolangValue value = values.get(name);
        return value == null ? MolangValue.ZERO : value;
    }

    /** @return the value, or null if never set (a missing value: {@code v.x ?? 1} gives 1) */
    public MolangValue getOrNull(String name) {
        return values.get(name);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public void set(String name, MolangValue value) {
        values.put(name, value == null ? MolangValue.ZERO : value);
    }

    /** Makes the variable missing again (assigning a missing value does this). */
    public void remove(String name) {
        values.remove(name);
    }

    public void clear() {
        values.clear();
    }
}

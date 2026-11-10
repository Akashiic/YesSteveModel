package com.akashic.futura.molang;

import java.util.HashMap;
import java.util.Map;

/**
 * Named Molang variables ({@code v.name}, also dotted struct paths such as {@code v.a.b}, stored by full path).
 * <p>
 * Keys have no namespace prefix and are lower-case: {@code v.Speed} and {@code variable.speed} are {@code "speed"},
 * {@code v.a.b} is {@code "a.b"}. Roaming variables ({@code v.roaming.x}, {@code roaming.x}, {@code r.x}) are
 * {@code "roaming.x"}. Not thread-safe: one instance per entity.
 * <p>
 * A second store of the same entity (the first-person arms) can see the roaming variables of the first one: build it
 * with {@link #MolangVariables(MolangVariables)}; every {@code roaming.*} read and write then goes to the shared store,
 * the other names stay local.
 */
public final class MolangVariables {

    static final String ROAMING_PREFIX = "roaming.";

    private final Map<String, MolangValue> values = new HashMap<>();
    private final MolangVariables roaming;

    public MolangVariables() {
        this(null);
    }

    /** @param roaming the store that owns the {@code roaming.*} variables, or null to keep them here */
    public MolangVariables(MolangVariables roaming) {
        this.roaming = roaming;
    }

    private Map<String, MolangValue> storeOf(String name) {
        return roaming != null && name.startsWith(ROAMING_PREFIX) ? roaming.storeOf(name) : values;
    }

    /** @return the value, or {@link MolangValue#ZERO} if never set */
    public MolangValue get(String name) {
        MolangValue value = storeOf(name).get(name);
        return value == null ? MolangValue.ZERO : value;
    }

    /** @return the value, or null if never set (a missing value: {@code v.x ?? 1} gives 1) */
    public MolangValue getOrNull(String name) {
        return storeOf(name).get(name);
    }

    public boolean has(String name) {
        return storeOf(name).containsKey(name);
    }

    public void set(String name, MolangValue value) {
        storeOf(name).put(name, value == null ? MolangValue.ZERO : value);
    }

    /** Makes the variable missing again (assigning a missing value does this). */
    public void remove(String name) {
        storeOf(name).remove(name);
    }

    /**
     * The numeric roaming variables ({@code roaming.*}) of the store that owns them, by name without
     * {@code roaming.}, into {@code out}; strings and vectors are skipped.
     */
    public void roamingNumbers(Map<String, Float> out) {
        if (roaming != null) {
            roaming.roamingNumbers(out);
            return;
        }
        for (Map.Entry<String, MolangValue> e : values.entrySet()) {
            if (e.getKey()
                .startsWith(ROAMING_PREFIX) && e.getValue().kind == MolangValue.Kind.NUMBER) {
                out.put(
                    e.getKey()
                        .substring(ROAMING_PREFIX.length()),
                    (float) e.getValue()
                        .asNumber());
            }
        }
    }

    /** Clears this store's own variables (shared roaming variables stay). */
    public void clear() {
        values.clear();
    }
}

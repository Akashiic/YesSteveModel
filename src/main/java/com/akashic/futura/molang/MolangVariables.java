package com.akashic.futura.molang;

import java.util.HashMap;
import java.util.Map;

/** Named Molang variables ({@code v.name}, also dotted struct paths such as {@code v.a.b}, stored by full path). */
public final class MolangVariables {

    private final Map<String, MolangValue> values = new HashMap<>();

    /** @return the value, or {@link MolangValue#ZERO} if never set */
    public MolangValue get(String name) {
        MolangValue value = values.get(name);
        return value == null ? MolangValue.ZERO : value;
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public void set(String name, MolangValue value) {
        values.put(name, value == null ? MolangValue.ZERO : value);
    }

    public void clear() {
        values.clear();
    }
}

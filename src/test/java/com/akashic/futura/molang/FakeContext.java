package com.akashic.futura.molang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/** A small host for tests: functions by {@code "namespace.name"}, persistent variables and a call log. */
final class FakeContext implements MolangContext {

    final MolangVariables variables = new MolangVariables();
    final Map<String, Function<MolangValue[], MolangValue>> functions = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    final Map<String, FakeContext> targets = new HashMap<>();
    Random random = new Random(42);

    FakeContext value(String key, double value) {
        function(key, args -> MolangValue.of(value));
        return this;
    }

    FakeContext value(String key, String value) {
        function(key, args -> MolangValue.of(value));
        return this;
    }

    FakeContext function(String key, Function<MolangValue[], MolangValue> function) {
        functions.put(normalize(key), function);
        return this;
    }

    /** Keys may use the {@code q.} and {@code c.} aliases; calls arrive as {@code query} and {@code context}. */
    private static String normalize(String key) {
        if (key.startsWith("q.")) {
            return "query." + key.substring(2);
        }
        if (key.startsWith("c.")) {
            return "context." + key.substring(2);
        }
        return key;
    }

    @Override
    public MolangValue call(String namespace, String name, MolangValue[] args) {
        String key = namespace + "." + name;
        calls.add(key + Arrays.toString(args));
        Function<MolangValue[], MolangValue> function = functions.get(key);
        return function == null ? null : function.apply(args);
    }

    @Override
    public MolangVariables variables() {
        return variables;
    }

    @Override
    public MolangContext withTarget(MolangValue reference) {
        return targets.get(reference.asString());
    }

    @Override
    public Random random() {
        return random;
    }
}

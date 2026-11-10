package com.akashic.futura.molang;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What an expression can see while it runs: the host's functions and the entity's persistent variables. Implemented
 * by the animation runtime (one per entity and frame). The interpreter handles {@code math.*}, {@code temp.*} /
 * {@code t.*} and control flow itself, and asks the context for everything else.
 */
public interface MolangContext {

    /**
     * A host function or property, e.g. {@code query.ground_speed} ({@code namespace = "query"},
     * {@code name = "ground_speed"}, no arguments) or {@code ysm.second_order('hair', x, 2, 0.4, 1)}.
     * <p>
     * Namespaces arrive normalised and lower-case: {@code q} is {@code query}, {@code c} is {@code context};
     * other namespaces ({@code ysm}, {@code ctrl}, {@code fn}, {@code args}...) as written. A bare identifier with no
     * namespace (upstream's {@code global:} symbols, e.g. {@code this}) arrives with namespace {@code "global"}.
     * {@code math.*} names the interpreter does not know are forwarded with namespace {@code "math"}. Names are
     * lower-case. A property ({@code q.x}) and an empty call ({@code q.x()}) are the same call.
     * <p>
     * The interpreter reuses the {@code args} array between evaluations: read it during the call, never keep it.
     * Missing arguments arrive as {@link MolangValue#ZERO}; numbers are always finite (a non-finite argument aborts
     * the program before the call, as upstream).
     *
     * @param args the evaluated arguments (empty for a property)
     * @return the value, or {@code null} if unknown (the expression then sees a missing value: 0, and the right side
     *         of {@code ??}). A non-finite number is also treated as missing.
     */
    MolangValue call(String namespace, String name, MolangValue[] args);

    /**
     * {@code symbol[index]} on a host symbol read without arguments, e.g. {@code args[0]} (the arguments of a user
     * function or event handler: namespace {@code "global"}, name {@code "args"}).
     *
     * @return the element, or null when there is none (a missing value)
     */
    default MolangValue index(String namespace, String name, int index) {
        return null;
    }

    /** {@code variable.*} / {@code v.*}: persistent per entity across frames. */
    MolangVariables variables();

    /**
     * The context of another entity for the arrow operator ({@code left -> right} evaluates {@code right} against the
     * entity that {@code left} refers to).
     *
     * @param reference the value of the left side (never null)
     * @return the other entity's context, or null when unsupported or unknown (the arrow then gives a missing value)
     */
    default MolangContext withTarget(MolangValue reference) {
        return null;
    }

    /**
     * Random source of {@code math.random}, {@code math.random_integer} and {@code math.die_roll*}. Upstream uses the
     * entity's animation processor random; override it to give each entity its own (seeded) source.
     */
    default Random random() {
        return ThreadLocalRandom.current();
    }
}

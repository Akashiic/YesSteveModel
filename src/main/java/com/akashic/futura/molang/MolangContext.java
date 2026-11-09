package com.akashic.futura.molang;

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
     * other namespaces ({@code ysm}, {@code ctrl}, {@code fn}...) as written. Names are lower-case.
     *
     * @param args the evaluated arguments (empty for a property)
     * @return the value, or {@code null} if unknown (the expression then sees 0)
     */
    MolangValue call(String namespace, String name, MolangValue[] args);

    /** {@code variable.*} / {@code v.*}: persistent per entity across frames. */
    MolangVariables variables();
}

package com.akashic.futura.molang;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point of the Molang interpreter (the YSM dialect).
 * <p>
 * Parse once (at model load), evaluate many times per frame: {@link #parse} compiles the source into a tree of nodes
 * and caches the program by source string; {@link MolangExpression#evaluate} and {@link MolangExpression#evalNumber}
 * run it against a {@link MolangContext}. A compiled program is immutable and may be shared between threads and
 * entities; {@code temp.*} variables live for one evaluation, {@code variable.*} ones in the context's
 * {@link MolangVariables}.
 * <p>
 * Fail-soft, as upstream: a program that does not compile is the constant 0, and evaluation never throws (unknown
 * names and wrong arities are missing values, an execution failure or a host exception makes the program 0).
 */
public final class Molang {

    /** Programs kept by source. Cleared when full: parsing happens on model load, so this is a soft bound. */
    private static final int CACHE_LIMIT = 16_384;

    private static final ConcurrentHashMap<String, MolangExpression> CACHE = new ConcurrentHashMap<>();

    private static final MolangExpression FAILED = MolangExpression.constant(0);

    private Molang() {}

    /**
     * Parses a Molang program. Never throws: a program that does not parse becomes the constant 0 (fail-soft, as
     * upstream). Cached by source string; thread-safe.
     */
    public static MolangExpression parse(String source) {
        if (source == null) {
            return FAILED;
        }
        MolangExpression cached = CACHE.get(source);
        if (cached != null) {
            return cached;
        }
        MolangExpression program;
        try {
            program = MolangParser.compile(source);
        } catch (MolangSyntaxException | RuntimeException | StackOverflowError e) {
            program = FAILED;
        }
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        MolangExpression previous = CACHE.putIfAbsent(source, program);
        return previous != null ? previous : program;
    }

    /**
     * Compiles a program without the cache, reporting why it does not compile (for tools and tests; the runtime uses
     * {@link #parse}).
     *
     * @throws MolangSyntaxException if the source is not a valid program
     */
    public static MolangExpression compile(String source) throws MolangSyntaxException {
        if (source == null) {
            throw new MolangSyntaxException("null source", 0);
        }
        try {
            return MolangParser.compile(source);
        } catch (StackOverflowError e) {
            throw new MolangSyntaxException("source too deeply nested", 0);
        }
    }

    /** @return true if {@link #parse} gives a real program for this source (false: it fell back to 0) */
    public static boolean isValid(String source) {
        try {
            compile(source);
            return true;
        } catch (MolangSyntaxException e) {
            return false;
        }
    }
}

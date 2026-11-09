package com.akashic.futura.molang;

/**
 * Fails the running program: upstream's execution failure (non-finite value at a boundary, exhausted budget, failing
 * function). The program then evaluates to 0. Shared and stackless: throwing it allocates nothing.
 */
final class Abort extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final Abort INSTANCE = new Abort();

    private Abort() {
        super("molang program failed", null, false, false);
    }
}

package com.akashic.futura.molang;

/** A Molang program that does not compile, from {@link Molang#compile}. {@link Molang#parse} never throws it. */
public final class MolangSyntaxException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int offset;

    MolangSyntaxException(String message, int offset) {
        super(message + " at offset " + offset);
        this.offset = offset;
    }

    /** @return the character offset in the source where the problem was found */
    public int offset() {
        return offset;
    }
}

package com.akashic.futura.molang;

/**
 * Entry point of the Molang interpreter.
 * <p>
 * Placeholder until the interpreter lands: numbers parse, anything else is 0.
 */
public final class Molang {

    private Molang() {}

    /**
     * Parses a Molang program. Never throws: a program that does not parse becomes the constant 0 (fail-soft, as
     * upstream).
     */
    public static MolangExpression parse(String source) {
        if (source == null) {
            return MolangExpression.constant(0);
        }
        try {
            return MolangExpression.constant(Double.parseDouble(source.trim()));
        } catch (NumberFormatException e) {
            return MolangExpression.constant(0);
        }
    }
}

package com.akashic.futura.molang;

/** A parsed Molang program (one expression or several statements). Thread-confined; never throws when evaluated. */
public interface MolangExpression {

    /** Runs the program; a {@code return} gives its value, otherwise the value of the last statement. */
    MolangValue evaluate(MolangContext context);

    default double evalNumber(MolangContext context) {
        return evaluate(context).asNumber();
    }

    /** @return true if the program is a constant (no context access), so callers may cache its value */
    default boolean isConstant() {
        return false;
    }

    static MolangExpression constant(double value) {
        MolangValue v = MolangValue.of(value);
        return new MolangExpression() {

            @Override
            public MolangValue evaluate(MolangContext context) {
                return v;
            }

            @Override
            public boolean isConstant() {
                return true;
            }

            @Override
            public String toString() {
                return v.toString();
            }
        };
    }
}

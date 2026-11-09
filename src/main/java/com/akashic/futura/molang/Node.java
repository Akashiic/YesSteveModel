package com.akashic.futura.molang;

/**
 * A compiled Molang node. Immutable and shared between threads; all evaluation state lives in the {@link Frame}.
 * <p>
 * Three ways to run a node: {@link #eval} gives the full value ({@code null} = missing, upstream's UNSET),
 * {@link #num} only the number (missing is 0) without allocating, {@link #exec} runs it as a statement.
 */
abstract class Node {

    /** @return the value, or null when missing */
    abstract MolangValue eval(Frame f);

    /** @return the number (missing is 0, a string is 1 when non-empty) */
    double num(Frame f) {
        MolangValue v = eval(f);
        return v == null ? 0 : v.asNumber();
    }

    final boolean bool(Frame f) {
        return num(f) != 0;
    }

    /** Runs the node for its effects. */
    void exec(Frame f) {
        num(f);
    }

    /** @return true if {@link #eval} always gives a present number (never missing, never a string) */
    boolean isNumber() {
        return false;
    }

    /** @return true if the node can be evaluated now (pure, and all of its inputs are constants) */
    boolean foldable() {
        return false;
    }

    static boolean allConstant(Node... nodes) {
        for (Node node : nodes) {
            if (!(node instanceof Nodes.Const)) {
                return false;
            }
        }
        return true;
    }

    static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}

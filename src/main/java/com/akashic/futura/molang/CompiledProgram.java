package com.akashic.futura.molang;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A parsed program: its root statements and what an evaluation needs. Immutable and safe to share between threads
 * (the parse cache does); each evaluation borrows a {@link Frame}, normally the one spare frame kept here.
 * <p>
 * Failures, as upstream: an execution failure ({@link Abort}: budgets, non-finite values handed to functions, failing
 * math) or a host exception gives 0, and so does a non-finite result. Variables written before the failure stay
 * written (upstream does not roll them back either).
 */
final class CompiledProgram implements MolangExpression {

    private final String source;
    private final Node[] statements;
    private final int temps;
    private final int[] scratchSizes;
    private final MolangValue constant;
    private final boolean isConstant;
    private final AtomicReference<Frame> spare = new AtomicReference<>();

    CompiledProgram(String source, Node[] statements, int temps, int[] scratchSizes) {
        this.source = source;
        this.statements = statements;
        this.temps = temps;
        this.scratchSizes = scratchSizes;
        this.isConstant = statements.length == 1 && statements[0] instanceof Nodes.Const;
        this.constant = isConstant ? result(((Nodes.Const) statements[0]).value) : null;
    }

    @Override
    public MolangValue evaluate(MolangContext context) {
        if (isConstant) {
            return constant;
        }
        Frame f = claim();
        f.begin(context, false);
        try {
            MolangValue value = null;
            int last = statements.length - 1;
            for (int i = 0; i < last && f.control == Frame.NONE; i++) {
                statements[i].exec(f);
            }
            if (f.control == Frame.NONE) {
                value = statements[last].eval(f);
            }
            if (f.control == Frame.RETURN) {
                value = f.returnValue;
            }
            return result(value);
        } catch (Exception | StackOverflowError | LinkageError | AssertionError e) {
            return MolangValue.ZERO;
        } finally {
            release(f);
        }
    }

    @Override
    public double evalNumber(MolangContext context) {
        if (isConstant) {
            return constant.asNumber();
        }
        Frame f = claim();
        f.begin(context, true);
        try {
            double value = 0;
            int last = statements.length - 1;
            for (int i = 0; i < last && f.control == Frame.NONE; i++) {
                statements[i].exec(f);
            }
            if (f.control == Frame.NONE) {
                value = statements[last].num(f);
            }
            if (f.control == Frame.RETURN) {
                value = f.returnNumber;
            }
            return Node.finite(value) ? value : 0;
        } catch (Exception | StackOverflowError | LinkageError | AssertionError e) {
            return 0;
        } finally {
            release(f);
        }
    }

    @Override
    public boolean isConstant() {
        return isConstant;
    }

    /** The program's final value: missing and non-finite numbers are 0. */
    private static MolangValue result(MolangValue value) {
        if (value == null) {
            return MolangValue.ZERO;
        }
        if (value.kind == MolangValue.Kind.NUMBER && !Node.finite(value.asNumber())) {
            return MolangValue.ZERO;
        }
        return value;
    }

    private Frame claim() {
        Frame f = spare.getAndSet(null);
        return f != null ? f : new Frame(temps, scratchSizes);
    }

    private void release(Frame f) {
        f.end();
        spare.lazySet(f);
    }

    @Override
    public String toString() {
        return source;
    }
}

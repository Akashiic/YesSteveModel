package com.akashic.futura.molang;

import java.util.Arrays;
import java.util.Random;

/**
 * The state of one evaluation: the context, the {@code temp.*} slots, control flow and budgets. A program keeps one
 * spare frame and reuses it, so a steady evaluation allocates no frame.
 */
final class Frame {

    /** Budgets per evaluation, as upstream's {@code ExpressionPolicy.EXECUTION_LIMITS}. Exceeding one fails. */
    static final int MAX_LOOP_ITERATIONS = 10_000;
    static final int MAX_PROVIDER_CALLS = 10_000;
    static final int MAX_CONTROL_TRANSFERS = 10_000;

    static final int NONE = 0, BREAK = 1, CONTINUE = 2, RETURN = 3;

    /** Marks a temp slot whose value is the number in {@link #tempNum}. */
    static final MolangValue NUMBER_SLOT = MolangValue.of("<number slot>");

    MolangContext context;
    final MolangValue[] tempVal;
    final double[] tempNum;
    final MolangValue[][] scratch;

    /** True while running for {@link MolangExpression#evalNumber}: {@code return} then keeps only the number. */
    boolean wantNumber;
    int control;
    MolangValue returnValue;
    double returnNumber;

    private int loops;
    private int calls;
    private int transfers;

    Frame(int temps, int[] scratchSizes) {
        tempVal = new MolangValue[temps];
        tempNum = new double[temps];
        scratch = new MolangValue[scratchSizes.length][];
        for (int i = 0; i < scratchSizes.length; i++) {
            scratch[i] = new MolangValue[scratchSizes[i]];
        }
    }

    void begin(MolangContext context, boolean wantNumber) {
        this.context = context;
        this.wantNumber = wantNumber;
        control = NONE;
        loops = 0;
        calls = 0;
        transfers = 0;
    }

    void end() {
        context = null;
        returnValue = null;
        Arrays.fill(tempVal, null);
        for (MolangValue[] args : scratch) {
            Arrays.fill(args, null);
        }
    }

    Random random() {
        return context == null ? new Random(0) : context.random();
    }

    void countLoop() {
        if (++loops > MAX_LOOP_ITERATIONS) {
            throw Abort.INSTANCE;
        }
    }

    void countCall() {
        if (++calls > MAX_PROVIDER_CALLS) {
            throw Abort.INSTANCE;
        }
    }

    void transfer(int kind) {
        if (++transfers > MAX_CONTROL_TRANSFERS) {
            throw Abort.INSTANCE;
        }
        control = kind;
    }
}

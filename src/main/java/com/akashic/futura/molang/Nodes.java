package com.akashic.futura.molang;

/**
 * The node kinds. Numbers follow upstream's 32-bit floats: every arithmetic result is rounded to {@code float}.
 */
final class Nodes {

    private Nodes() {}

    static final MolangValue[] NO_ARGS = new MolangValue[0];

    /** Evaluates a node whose inputs are all constants; returns it unchanged if it fails. */
    static Node fold(Node node) {
        if (!node.foldable()) {
            return node;
        }
        Frame frame = new Frame(0, new int[0]);
        frame.begin(null, false);
        try {
            return new Const(node.eval(frame));
        } catch (RuntimeException e) {
            return node;
        }
    }

    /** {@code x}, {@code y}, {@code z} as a vector component index, else -1. */
    static int component(String member) {
        switch (member) {
            case "x":
                return 0;
            case "y":
                return 1;
            case "z":
                return 2;
            default:
                return -1;
        }
    }

    static MolangValue member(MolangValue value, int component) {
        if (value == null || component < 0 || value.kind != MolangValue.Kind.VECTOR) {
            return null;
        }
        return MolangValue.of(value.component(component));
    }

    /** Upstream's value equality: same-kind content, missing as 0 / empty string, otherwise numbers. */
    static boolean equal(MolangValue a, MolangValue b) {
        if (a == null || b == null) {
            MolangValue other = a == null ? b : a;
            if (other == null) {
                return true;
            }
            switch (other.kind) {
                case STRING:
                    return other.asString()
                        .isEmpty();
                case NUMBER:
                    return other.asNumber() == 0;
                default:
                    return false;
            }
        }
        if (a.kind == MolangValue.Kind.STRING && b.kind == MolangValue.Kind.STRING) {
            return a.asString()
                .equals(b.asString());
        }
        if (a.kind == MolangValue.Kind.VECTOR && b.kind == MolangValue.Kind.VECTOR) {
            return (float) a.component(0) == (float) b.component(0) && (float) a.component(1) == (float) b.component(1)
                && (float) a.component(2) == (float) b.component(2);
        }
        return (float) a.asNumber() == (float) b.asNumber();
    }

    /** A host value as the interpreter sees it: non-finite numbers are missing, numbers are rounded to float. */
    static MolangValue normalize(MolangValue value) {
        if (value == null || value.kind != MolangValue.Kind.NUMBER) {
            return value;
        }
        double d = value.asNumber();
        float f = (float) d;
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            return null;
        }
        return f == d ? value : MolangValue.of(f);
    }

    static double normalizedNumber(MolangValue value) {
        if (value == null) {
            return 0;
        }
        float f = (float) value.asNumber();
        return Float.isNaN(f) || Float.isInfinite(f) ? 0 : f;
    }

    /** A node that always gives a present number. */
    abstract static class NumberNode extends Node {

        @Override
        final MolangValue eval(Frame f) {
            return MolangValue.of(num(f));
        }

        @Override
        abstract double num(Frame f);

        @Override
        final boolean isNumber() {
            return true;
        }
    }

    static final class Const extends Node {

        final MolangValue value;
        private final double number;

        Const(MolangValue value) {
            this.value = value;
            this.number = value == null ? 0 : value.asNumber();
        }

        @Override
        MolangValue eval(Frame f) {
            return value;
        }

        @Override
        double num(Frame f) {
            return number;
        }

        @Override
        void exec(Frame f) {}

        @Override
        boolean isNumber() {
            return value != null && value.kind == MolangValue.Kind.NUMBER;
        }
    }

    static final class Negate extends NumberNode {

        final Node operand;

        Negate(Node operand) {
            this.operand = operand;
        }

        @Override
        double num(Frame f) {
            return (float) -operand.num(f);
        }

        @Override
        boolean foldable() {
            return allConstant(operand);
        }
    }

    static final class Positive extends NumberNode {

        final Node operand;

        Positive(Node operand) {
            this.operand = operand;
        }

        @Override
        double num(Frame f) {
            return (float) operand.num(f);
        }

        @Override
        boolean foldable() {
            return allConstant(operand);
        }
    }

    static final class Not extends NumberNode {

        final Node operand;

        Not(Node operand) {
            this.operand = operand;
        }

        @Override
        double num(Frame f) {
            return operand.bool(f) ? 0 : 1;
        }

        @Override
        boolean foldable() {
            return allConstant(operand);
        }
    }

    static final int ADD = 0, SUB = 1, MUL = 2, DIV = 3, LT = 4, LTE = 5, GT = 6, GTE = 7;

    /** Arithmetic and ordering. Division follows IEEE: {@code 1/0} is infinite (see {@link CompiledProgram}). */
    static final class Binary extends NumberNode {

        final int op;
        final Node left;
        final Node right;

        Binary(int op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        double num(Frame f) {
            double a = left.num(f);
            double b = right.num(f);
            switch (op) {
                case ADD:
                    return (float) (a + b);
                case SUB:
                    return (float) (a - b);
                case MUL:
                    return (float) (a * b);
                case DIV:
                    return (float) (a / b);
                case LT:
                    return (float) a < (float) b ? 1 : 0;
                case LTE:
                    return (float) a <= (float) b ? 1 : 0;
                case GT:
                    return (float) a > (float) b ? 1 : 0;
                default:
                    return (float) a >= (float) b ? 1 : 0;
            }
        }

        @Override
        boolean foldable() {
            return allConstant(left, right);
        }
    }

    static final class Equals extends NumberNode {

        final Node left;
        final Node right;
        final boolean negate;
        private final boolean numeric;

        Equals(Node left, Node right, boolean negate) {
            this.left = left;
            this.right = right;
            this.negate = negate;
            this.numeric = left.isNumber() && right.isNumber();
        }

        @Override
        double num(Frame f) {
            boolean equal;
            if (numeric) {
                equal = (float) left.num(f) == (float) right.num(f);
            } else {
                MolangValue a = left.eval(f);
                equal = equal(a, right.eval(f));
            }
            return equal != negate ? 1 : 0;
        }

        @Override
        boolean foldable() {
            return allConstant(left, right);
        }
    }

    static final class And extends NumberNode {

        final Node left;
        final Node right;

        And(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        double num(Frame f) {
            return left.bool(f) && right.bool(f) ? 1 : 0;
        }

        @Override
        boolean foldable() {
            return allConstant(left, right);
        }
    }

    static final class Or extends NumberNode {

        final Node left;
        final Node right;

        Or(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        double num(Frame f) {
            return left.bool(f) || right.bool(f) ? 1 : 0;
        }

        @Override
        boolean foldable() {
            return allConstant(left, right);
        }
    }

    /** {@code c ? a : b}; the binary form {@code c ? a} has a missing {@code b}. */
    static final class Conditional extends Node {

        final Node condition;
        final Node ifTrue;
        final Node ifFalse;

        Conditional(Node condition, Node ifTrue, Node ifFalse) {
            this.condition = condition;
            this.ifTrue = ifTrue;
            this.ifFalse = ifFalse;
        }

        @Override
        MolangValue eval(Frame f) {
            return condition.bool(f) ? ifTrue.eval(f) : ifFalse.eval(f);
        }

        @Override
        double num(Frame f) {
            return condition.bool(f) ? ifTrue.num(f) : ifFalse.num(f);
        }

        @Override
        void exec(Frame f) {
            if (condition.bool(f)) {
                ifTrue.exec(f);
            } else {
                ifFalse.exec(f);
            }
        }

        @Override
        boolean isNumber() {
            return ifTrue.isNumber() && ifFalse.isNumber();
        }

        @Override
        boolean foldable() {
            return allConstant(condition, ifTrue, ifFalse);
        }
    }

    /** {@code a ?? b}: {@code b} only when {@code a} is missing (0, false and '' keep {@code a}). */
    static final class Coalesce extends Node {

        final Node left;
        final Node right;

        Coalesce(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        MolangValue eval(Frame f) {
            MolangValue value = left.eval(f);
            return value != null ? value : right.eval(f);
        }

        @Override
        double num(Frame f) {
            if (left.isNumber()) {
                return left.num(f);
            }
            MolangValue value = left.eval(f);
            return value != null ? value.asNumber() : right.num(f);
        }

        @Override
        boolean isNumber() {
            return left.isNumber();
        }

        @Override
        boolean foldable() {
            return allConstant(left, right);
        }
    }

    /** {@code { a; b; }}: runs the statements; its value is missing. */
    static final class Block extends Node {

        final Node[] statements;

        Block(Node[] statements) {
            this.statements = statements;
        }

        @Override
        MolangValue eval(Frame f) {
            exec(f);
            return null;
        }

        @Override
        double num(Frame f) {
            exec(f);
            return 0;
        }

        @Override
        void exec(Frame f) {
            for (Node statement : statements) {
                statement.exec(f);
                if (f.control != Frame.NONE) {
                    return;
                }
            }
        }
    }

    static final class Return extends Node {

        final Node value;

        Return(Node value) {
            this.value = value;
        }

        @Override
        MolangValue eval(Frame f) {
            exec(f);
            return null;
        }

        @Override
        double num(Frame f) {
            exec(f);
            return 0;
        }

        @Override
        void exec(Frame f) {
            if (value == null) {
                f.returnValue = null;
                f.returnNumber = 0;
            } else if (f.wantNumber) {
                f.returnNumber = value.num(f);
                f.returnValue = null;
            } else {
                f.returnValue = value.eval(f);
            }
            f.transfer(Frame.RETURN);
        }
    }

    /** {@code break} or {@code continue}. */
    static final class Jump extends Node {

        final int kind;

        Jump(int kind) {
            this.kind = kind;
        }

        @Override
        MolangValue eval(Frame f) {
            exec(f);
            return null;
        }

        @Override
        double num(Frame f) {
            exec(f);
            return 0;
        }

        @Override
        void exec(Frame f) {
            f.transfer(kind);
        }
    }

    /** {@code loop(n, body)}: {@code n} truncated toward zero; the iterations of a program share a budget. */
    static final class Loop extends Node {

        final Node count;
        final Node body;

        Loop(Node count, Node body) {
            this.count = count;
            this.body = body;
        }

        @Override
        MolangValue eval(Frame f) {
            exec(f);
            return null;
        }

        @Override
        double num(Frame f) {
            exec(f);
            return 0;
        }

        @Override
        void exec(Frame f) {
            double n = count.num(f);
            if (!finite(n)) {
                throw Abort.INSTANCE;
            }
            int times = (int) n;
            for (int i = 0; i < times; i++) {
                f.countLoop();
                body.exec(f);
                int control = f.control;
                if (control != Frame.NONE) {
                    if (control == Frame.RETURN) {
                        return;
                    }
                    f.control = Frame.NONE;
                    if (control == Frame.BREAK) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * {@code for_each(t.x, collection, body)}. Upstream iterates read-only arrays returned by Java extensions; no
     * host value here is an array, so the collection is evaluated and the body never runs.
     */
    static final class ForEach extends Node {

        final int slot;
        final Node collection;
        final Node body;

        ForEach(int slot, Node collection, Node body) {
            this.slot = slot;
            this.collection = collection;
            this.body = body;
        }

        @Override
        MolangValue eval(Frame f) {
            exec(f);
            return null;
        }

        @Override
        double num(Frame f) {
            exec(f);
            return 0;
        }

        @Override
        void exec(Frame f) {
            collection.eval(f);
        }
    }

    /**
     * {@code v.path}: a persistent variable by full path. {@code v.a.b} that was never set reads member {@code b} of
     * {@code v.a} (so {@code v.pos = ysm.bone_pivot_abs('x'); v.pos.x} works).
     */
    static final class VariableRead extends Node {

        final String key;
        final String parent;
        final int component;

        VariableRead(String key) {
            this.key = key;
            int dot = key.lastIndexOf('.');
            this.parent = dot < 0 ? null : key.substring(0, dot);
            this.component = dot < 0 ? -1 : component(key.substring(dot + 1));
        }

        @Override
        MolangValue eval(Frame f) {
            MolangVariables variables = f.context.variables();
            MolangValue value = variables.getOrNull(key);
            if (value == null && parent != null) {
                value = member(variables.getOrNull(parent), component);
            }
            return value;
        }
    }

    static final class VariableAssign extends Node {

        final String key;
        final Node value;

        VariableAssign(String key, Node value) {
            this.key = key;
            this.value = value;
        }

        @Override
        MolangValue eval(Frame f) {
            MolangValue v = value.eval(f);
            store(f, v);
            return v;
        }

        @Override
        double num(Frame f) {
            if (value.isNumber()) {
                double d = value.num(f);
                store(f, MolangValue.of(d));
                return d;
            }
            MolangValue v = value.eval(f);
            store(f, v);
            return v == null ? 0 : v.asNumber();
        }

        private void store(Frame f, MolangValue v) {
            if (f.control != Frame.NONE) {
                return;
            }
            MolangVariables variables = f.context.variables();
            if (v == null || v.kind == MolangValue.Kind.NUMBER && !finite(v.asNumber())) {
                // A non-finite F32 is a typed missing upstream, read back as 0: a NaN must not latch. Spring physics
                // written in Molang divide by constants another animation sets a few frames later (0/0 on the first
                // frames); a stored NaN fed itself back forever and hid every bone it drives.
                variables.remove(key);
            } else {
                variables.set(key, v);
            }
        }
    }

    /** {@code t.path}: a slot of this evaluation; {@code t.a.b} falls back to member {@code b} of {@code t.a}. */
    static final class TempRead extends Node {

        final String path;
        final int slot;
        final int parentSlot;
        final int component;

        TempRead(String path, int slot, int parentSlot, int component) {
            this.path = path;
            this.slot = slot;
            this.parentSlot = parentSlot;
            this.component = component;
        }

        @Override
        MolangValue eval(Frame f) {
            MolangValue value = read(f, slot);
            if (value == null && parentSlot >= 0) {
                value = member(read(f, parentSlot), component);
            }
            return value;
        }

        @Override
        double num(Frame f) {
            MolangValue value = f.tempVal[slot];
            if (value == Frame.NUMBER_SLOT) {
                return f.tempNum[slot];
            }
            if (value == null && parentSlot >= 0) {
                value = member(read(f, parentSlot), component);
            }
            return value == null ? 0 : value.asNumber();
        }

        private static MolangValue read(Frame f, int slot) {
            MolangValue value = f.tempVal[slot];
            return value == Frame.NUMBER_SLOT ? MolangValue.of(f.tempNum[slot]) : value;
        }
    }

    static final class TempAssign extends Node {

        final int slot;
        final Node value;

        TempAssign(int slot, Node value) {
            this.slot = slot;
            this.value = value;
        }

        @Override
        MolangValue eval(Frame f) {
            if (value.isNumber()) {
                return MolangValue.of(num(f));
            }
            MolangValue v = value.eval(f);
            if (f.control == Frame.NONE) {
                f.tempVal[slot] = v;
            }
            return v;
        }

        @Override
        double num(Frame f) {
            if (value.isNumber()) {
                double d = value.num(f);
                if (f.control == Frame.NONE) {
                    f.tempNum[slot] = d;
                    f.tempVal[slot] = Frame.NUMBER_SLOT;
                }
                return d;
            }
            MolangValue v = eval(f);
            return v == null ? 0 : v.asNumber();
        }
    }

    /** A host function or property, through {@link MolangContext#call}. */
    static final class HostCall extends Node {

        final String namespace;
        final String name;
        final Node[] args;
        final int scratch;

        HostCall(String namespace, String name, Node[] args, int scratch) {
            this.namespace = namespace;
            this.name = name;
            this.args = args;
            this.scratch = scratch;
        }

        @Override
        MolangValue eval(Frame f) {
            return normalize(call(f));
        }

        @Override
        double num(Frame f) {
            return normalizedNumber(call(f));
        }

        private MolangValue call(Frame f) {
            MolangValue[] values = args.length == 0 ? NO_ARGS : f.scratch[scratch];
            for (int i = 0; i < args.length; i++) {
                MolangValue v = args[i].eval(f);
                if (v == null) {
                    v = MolangValue.ZERO;
                } else if (v.kind == MolangValue.Kind.NUMBER && !finite(v.asNumber())) {
                    // upstream checks every F32 handed to Java: a non-finite one fails the program
                    throw Abort.INSTANCE;
                }
                values[i] = v;
            }
            f.countCall();
            return f.context.call(namespace, name, values);
        }
    }

    /** A built-in {@code math.*} function (see {@link MolangMath}). */
    static final class MathCall extends NumberNode {

        final MolangMath.Fn fn;
        final Node[] args;

        MathCall(MolangMath.Fn fn, Node[] args) {
            this.fn = fn;
            this.args = args;
        }

        @Override
        double num(Frame f) {
            double a = args.length > 0 ? arg(f, 0) : 0;
            double b = args.length > 1 ? arg(f, 1) : 0;
            double c = args.length > 2 ? arg(f, 2) : 0;
            return MolangMath.apply(fn.id, args.length, a, b, c, f);
        }

        private double arg(Frame f, int i) {
            double value = args[i].num(f);
            if (!finite(value)) {
                throw Abort.INSTANCE;
            }
            return value;
        }

        @Override
        boolean foldable() {
            return fn.pure && allConstant(args);
        }
    }

    /** {@code value.member}: a vector's {@code x}, {@code y} or {@code z}; anything else is missing. */
    static final class Member extends Node {

        final Node base;
        final int component;

        Member(Node base, int component) {
            this.base = base;
            this.component = component;
        }

        @Override
        MolangValue eval(Frame f) {
            return member(base.eval(f), component);
        }

        @Override
        double num(Frame f) {
            MolangValue value = base.eval(f);
            if (value == null || component < 0 || value.kind != MolangValue.Kind.VECTOR) {
                return 0;
            }
            return value.component(component);
        }
    }

    /**
     * {@code value[index]}: upstream indexes read-only arrays only. No value here is an array, so it is missing, except
     * a host symbol read without arguments ({@code args[0]} in a user function), which the context indexes
     * ({@link MolangContext#index}).
     */
    static final class Index extends Node {

        final Node base;
        final Node index;

        Index(Node base, Node index) {
            this.base = base;
            this.index = index;
        }

        @Override
        MolangValue eval(Frame f) {
            if (base instanceof HostCall && ((HostCall) base).args.length == 0) {
                HostCall symbol = (HostCall) base;
                double i = index.num(f);
                if (!finite(i)) {
                    throw Abort.INSTANCE;
                }
                f.countCall();
                return normalize(f.context.index(symbol.namespace, symbol.name, (int) i));
            }
            base.eval(f);
            index.num(f);
            return null;
        }
    }

    /** {@code left -> right}: {@code right} against the entity {@code left} refers to. */
    static final class Arrow extends Node {

        final Node left;
        final Node right;

        Arrow(Node left, Node right) {
            this.left = left;
            this.right = right;
        }

        @Override
        MolangValue eval(Frame f) {
            MolangContext target = target(f);
            if (target == null) {
                return null;
            }
            MolangContext saved = f.context;
            f.context = target;
            try {
                return right.eval(f);
            } finally {
                f.context = saved;
            }
        }

        @Override
        double num(Frame f) {
            MolangContext target = target(f);
            if (target == null) {
                return 0;
            }
            MolangContext saved = f.context;
            f.context = target;
            try {
                return right.num(f);
            } finally {
                f.context = saved;
            }
        }

        private MolangContext target(Frame f) {
            MolangValue reference = left.eval(f);
            return reference == null ? null : f.context.withTarget(reference);
        }
    }
}

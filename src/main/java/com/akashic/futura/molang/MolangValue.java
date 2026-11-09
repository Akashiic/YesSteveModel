package com.akashic.futura.molang;

import java.util.Arrays;

/**
 * A Molang value: a number, a string or a vector of three numbers. Immutable. Molang is fail-soft: anything that
 * goes wrong evaluates to {@link #ZERO}.
 */
public final class MolangValue {

    public enum Kind {
        NUMBER,
        STRING,
        VECTOR
    }

    public static final MolangValue ZERO = new MolangValue(Kind.NUMBER, 0, null, null);
    public static final MolangValue ONE = new MolangValue(Kind.NUMBER, 1, null, null);

    public final Kind kind;
    private final double number;
    private final String string;
    private final double[] vector;

    private MolangValue(Kind kind, double number, String string, double[] vector) {
        this.kind = kind;
        this.number = number;
        this.string = string;
        this.vector = vector;
    }

    public static MolangValue of(double number) {
        if (number == 0) {
            return ZERO;
        }
        if (number == 1) {
            return ONE;
        }
        return new MolangValue(Kind.NUMBER, number, null, null);
    }

    public static MolangValue of(boolean value) {
        return value ? ONE : ZERO;
    }

    public static MolangValue of(String string) {
        return new MolangValue(Kind.STRING, 0, string == null ? "" : string, null);
    }

    public static MolangValue vector(double x, double y, double z) {
        return new MolangValue(Kind.VECTOR, 0, null, new double[] { x, y, z });
    }

    /** The number; a string is 1 when non-empty (truthy) and 0 when empty; a vector gives its x. */
    public double asNumber() {
        switch (kind) {
            case NUMBER:
                return number;
            case STRING:
                return string.isEmpty() ? 0 : 1;
            default:
                return vector[0];
        }
    }

    public boolean asBoolean() {
        return asNumber() != 0;
    }

    /** The string, or the number written in Molang style for other kinds. */
    public String asString() {
        if (kind == Kind.STRING) {
            return string;
        }
        if (kind == Kind.VECTOR) {
            return Arrays.toString(vector);
        }
        return number == (long) number ? Long.toString((long) number) : Double.toString(number);
    }

    /** Component {@code i} (0..2) of a vector; a number gives itself for every component. */
    public double component(int i) {
        return kind == Kind.VECTOR ? vector[i] : asNumber();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MolangValue)) {
            return false;
        }
        MolangValue other = (MolangValue) o;
        if (kind != other.kind) {
            return false;
        }
        switch (kind) {
            case NUMBER:
                return Double.compare(number, other.number) == 0;
            case STRING:
                return string.equals(other.string);
            default:
                return Arrays.equals(vector, other.vector);
        }
    }

    @Override
    public int hashCode() {
        switch (kind) {
            case NUMBER:
                return Double.hashCode(number);
            case STRING:
                return string.hashCode();
            default:
                return Arrays.hashCode(vector);
        }
    }

    @Override
    public String toString() {
        return kind == Kind.STRING ? "'" + string + "'" : asString();
    }
}

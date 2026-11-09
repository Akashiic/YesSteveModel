/*
 * Adapted from Yes Steve Model, src/main/java/com/elfmcys/ysm/expression/host/builtin/MathBuiltins.java and
 * src/main/java/com/elfmcys/ysm/expression/host/builtin/ControlBuiltins.java (math.random, math.random_integer),
 * Apache-2.0. The functions upstream does not have follow Bedrock's Molang.
 */
package com.akashic.futura.molang;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The {@code math.*} functions. Upstream computes in 32-bit floats, and so do these: every result is rounded to
 * {@code float}. Angles are in <b>degrees</b>.
 * <p>
 * Upstream functions and their quirks:
 * <ul>
 * <li>{@code abs(a)}, {@code floor(a)}, {@code max(a, b)}, {@code min(a, b)}, {@code sqrt(a)}, {@code exp(a)},
 * {@code pow(a, b)}.</li>
 * <li>{@code clamp(v, lo, hi)} is {@code min(max(v, lo), hi)}: with {@code lo > hi} it gives {@code hi}.</li>
 * <li>{@code sin(deg)}, {@code cos(deg)} take degrees.</li>
 * <li>{@code lerp(a, b, t)} is {@code a + (b - a) * t}, not clamped.</li>
 * <li>{@code mod(a, b)} is Java's remainder: the sign follows {@code a} ({@code mod(-1, 3)} is -1).</li>
 * <li>{@code round(a)} rounds half up ({@code round(-2.5)} is -2), like {@code Math.round}.</li>
 * <li>{@code min_angle(deg)} wraps into {@code [-180, 180)}.</li>
 * <li>{@code random(lo, hi[, ignored])} is uniform in {@code [lo, hi)}; bounds are swapped when reversed; the third
 * argument is accepted and ignored.</li>
 * <li>{@code random_integer(lo, hi)} truncates both bounds toward zero, swaps them when reversed and gives
 * {@code lo + nextInt(hi - lo)}: the upper bound is <b>exclusive</b>, and equal bounds make the call fail (upstream's
 * {@code nextInt(0)} throws), so the whole program gives 0.</li>
 * <li>{@code math.pi} is a literal ({@code (float) Math.PI}), not a function: {@code math.pi()} is an unknown
 * call.</li>
 * </ul>
 * Bedrock functions upstream does not have (superset): {@code acos}, {@code asin}, {@code atan}, {@code atan2(y, x)}
 * (all returning degrees), {@code ceil}, {@code trunc} (toward zero), {@code ln}, {@code sign} (1 when positive, -1
 * otherwise, so {@code sign(0)} is -1, as Bedrock documents it), {@code copy_sign(a, b)}, {@code hermite_blend(t)}
 * ({@code 3t^2 - 2t^3}), {@code lerprotate(a, b, t)} (the short way round, result in {@code [-180, 180)}),
 * {@code inverse_lerp(a, b, v)} (0 when {@code a == b}), {@code die_roll(n, lo, hi)} (sum of {@code n} {@code random}),
 * {@code die_roll_integer(n, lo, hi)} (sum of {@code n} integers in {@code [lo, hi]}, inclusive as in Bedrock) and the
 * {@code ease_in_*}, {@code ease_out_*}, {@code ease_in_out_*} families ({@code quad}, {@code cubic}, {@code quart},
 * {@code quint}, {@code sine}, {@code expo}, {@code circ}, {@code back}, {@code elastic}, {@code bounce}) taking
 * {@code (start, end, t)}.
 * <p>
 * A call with the wrong number of arguments is a missing value and its arguments are not evaluated. A non-finite
 * argument or result (e.g. {@code sqrt(-1)}, {@code mod(1, 0)}, {@code ln(0)}) fails the whole program, which then
 * gives 0, as upstream (its {@code finite} check throws).
 */
public final class MolangMath {

    private MolangMath() {}

    static final float PI = (float) Math.PI;

    /** A known function: its id and arity. */
    static final class Fn {

        final int id;
        final int minArgs;
        final int maxArgs;
        final boolean pure;

        Fn(int id, int minArgs, int maxArgs, boolean pure) {
            this.id = id;
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.pure = pure;
        }
    }

    static final int ABS = 0, CLAMP = 1, COS = 2, EXP = 3, FLOOR = 4, LERP = 5, MAX = 6, MIN = 7, MOD = 8, POW = 9,
        ROUND = 10, SIN = 11, SQRT = 12, MIN_ANGLE = 13, RANDOM = 14, RANDOM_INTEGER = 15, ACOS = 16, ASIN = 17,
        ATAN = 18, ATAN2 = 19, CEIL = 20, TRUNC = 21, LN = 22, SIGN = 23, COPY_SIGN = 24, HERMITE_BLEND = 25,
        LERPROTATE = 26, INVERSE_LERP = 27, DIE_ROLL = 28, DIE_ROLL_INTEGER = 29, EASE = 100;

    private static final String[] EASE_NAMES = { "quad", "cubic", "quart", "quint", "sine", "expo", "circ", "back",
        "elastic", "bounce" };

    private static final Map<String, Fn> FUNCTIONS = new HashMap<>();

    static {
        fn("abs", ABS, 1);
        fn("clamp", CLAMP, 3);
        fn("cos", COS, 1);
        fn("exp", EXP, 1);
        fn("floor", FLOOR, 1);
        fn("lerp", LERP, 3);
        fn("max", MAX, 2);
        fn("min", MIN, 2);
        fn("mod", MOD, 2);
        fn("pow", POW, 2);
        fn("round", ROUND, 1);
        fn("sin", SIN, 1);
        fn("sqrt", SQRT, 1);
        fn("min_angle", MIN_ANGLE, 1);
        FUNCTIONS.put("random", new Fn(RANDOM, 2, 3, false));
        FUNCTIONS.put("random_integer", new Fn(RANDOM_INTEGER, 2, 2, false));
        fn("acos", ACOS, 1);
        fn("asin", ASIN, 1);
        fn("atan", ATAN, 1);
        fn("atan2", ATAN2, 2);
        fn("ceil", CEIL, 1);
        fn("trunc", TRUNC, 1);
        fn("ln", LN, 1);
        fn("sign", SIGN, 1);
        fn("copy_sign", COPY_SIGN, 2);
        fn("hermite_blend", HERMITE_BLEND, 1);
        fn("lerprotate", LERPROTATE, 3);
        fn("inverse_lerp", INVERSE_LERP, 3);
        FUNCTIONS.put("die_roll", new Fn(DIE_ROLL, 3, 3, false));
        FUNCTIONS.put("die_roll_integer", new Fn(DIE_ROLL_INTEGER, 3, 3, false));
        for (int i = 0; i < EASE_NAMES.length; i++) {
            fn("ease_in_" + EASE_NAMES[i], EASE + i * 3, 3);
            fn("ease_out_" + EASE_NAMES[i], EASE + i * 3 + 1, 3);
            fn("ease_in_out_" + EASE_NAMES[i], EASE + i * 3 + 2, 3);
        }
    }

    private static void fn(String name, int id, int arity) {
        FUNCTIONS.put(name, new Fn(id, arity, arity, true));
    }

    /** @return the function, or null if {@code math.<name>} is not built in */
    static Fn lookup(String name) {
        return FUNCTIONS.get(name);
    }

    /**
     * Runs a function on finite arguments. Throws {@link Abort} when upstream would fail (non-finite result, empty
     * {@code random_integer} range).
     */
    static double apply(int id, int argc, double a, double b, double c, Frame frame) {
        double r;
        switch (id) {
            case ABS:
                r = Math.abs((float) a);
                break;
            case CLAMP:
                r = clamp((float) a, (float) b, (float) c);
                break;
            case COS:
                r = cos((float) a);
                break;
            case EXP:
                r = Math.exp(a);
                break;
            case FLOOR:
                r = Math.floor(a);
                break;
            case LERP:
                r = lerp((float) a, (float) b, (float) c);
                break;
            case MAX:
                r = Math.max((float) a, (float) b);
                break;
            case MIN:
                r = Math.min((float) a, (float) b);
                break;
            case MOD:
                r = (float) a % (float) b;
                break;
            case POW:
                r = Math.pow(a, b);
                break;
            case ROUND:
                r = Math.round((float) a);
                break;
            case SIN:
                r = sin((float) a);
                break;
            case SQRT:
                r = Math.sqrt(a);
                break;
            case MIN_ANGLE:
                r = minAngle((float) a);
                break;
            case RANDOM:
                r = random(frame.random(), (float) a, (float) b);
                break;
            case RANDOM_INTEGER:
                r = randomInteger(frame.random(), (float) a, (float) b);
                break;
            case ACOS:
                r = Math.toDegrees(Math.acos(a));
                break;
            case ASIN:
                r = Math.toDegrees(Math.asin(a));
                break;
            case ATAN:
                r = Math.toDegrees(Math.atan(a));
                break;
            case ATAN2:
                r = Math.toDegrees(Math.atan2(a, b));
                break;
            case CEIL:
                r = Math.ceil(a);
                break;
            case TRUNC:
                r = a < 0 ? Math.ceil(a) : Math.floor(a);
                break;
            case LN:
                r = Math.log(a);
                break;
            case SIGN:
                r = a > 0 ? 1 : -1;
                break;
            case COPY_SIGN:
                r = Math.copySign((float) a, (float) b);
                break;
            case HERMITE_BLEND: {
                float t = (float) a;
                r = 3 * t * t - 2 * t * t * t;
                break;
            }
            case LERPROTATE:
                r = lerpRotate((float) a, (float) b, (float) c);
                break;
            case INVERSE_LERP:
                r = a == b ? 0 : (float) ((float) c - (float) a) / (float) ((float) b - (float) a);
                break;
            case DIE_ROLL:
                r = dieRoll(frame, a, (float) b, (float) c, false);
                break;
            case DIE_ROLL_INTEGER:
                r = dieRoll(frame, a, (float) b, (float) c, true);
                break;
            default:
                r = id >= EASE ? ease(id - EASE, (float) a, (float) b, (float) c) : 0;
        }
        float result = (float) r;
        if (Float.isNaN(result) || Float.isInfinite(result)) {
            throw Abort.INSTANCE;
        }
        return result;
    }

    // Upstream's scalar implementations

    public static float clamp(float value, float min, float max) {
        return Math.min(Math.max(value, min), max);
    }

    public static float cos(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }

    public static float sin(float degrees) {
        return (float) Math.sin(Math.toRadians(degrees));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static float minAngle(float degrees) {
        float wrapped = degrees % 360;
        return wrapped >= 180 ? wrapped - 360 : wrapped < -180 ? wrapped + 360 : wrapped;
    }

    static float random(Random random, float min, float max) {
        if (min > max) {
            float swap = min;
            min = max;
            max = swap;
        }
        return min + random.nextFloat() * (max - min);
    }

    static float randomInteger(Random random, float min, float max) {
        int lower = (int) min;
        int upper = (int) max;
        if (lower > upper) {
            int swap = lower;
            lower = upper;
            upper = swap;
        }
        if (upper - lower <= 0) {
            // upstream: Random.nextInt(0) (or an overflowed range) throws, failing the program
            throw Abort.INSTANCE;
        }
        return lower + random.nextInt(upper - lower);
    }

    // Bedrock functions

    static float lerpRotate(float a, float b, float t) {
        float from = minAngle(a);
        float delta = minAngle(minAngle(b) - from);
        return minAngle(from + delta * t);
    }

    private static double dieRoll(Frame frame, double count, float low, float high, boolean integer) {
        int n = (int) count;
        Random random = frame.random();
        float total = 0;
        for (int i = 0; i < n; i++) {
            frame.countLoop();
            if (integer) {
                int lower = (int) Math.min(low, high);
                int upper = (int) Math.max(low, high);
                long span = (long) upper - lower + 1;
                total += lower + (span > Integer.MAX_VALUE ? random.nextInt() : random.nextInt((int) span));
            } else {
                total += random(random, low, high);
            }
        }
        return total;
    }

    /** Bedrock's easing functions ({@code index} = family * 3 + {in, out, in_out}), from start to end. */
    static float ease(int index, float start, float end, float t) {
        int family = index / 3;
        int mode = index % 3;
        double x = t;
        double y;
        if (mode == 0) {
            y = easeIn(family, x);
        } else if (mode == 1) {
            y = 1 - easeIn(family, 1 - x);
        } else {
            y = x < 0.5 ? easeIn(family, 2 * x) / 2 : 1 - easeIn(family, 2 - 2 * x) / 2;
        }
        return (float) (start + (end - start) * y);
    }

    private static double easeIn(int family, double x) {
        switch (family) {
            case 0:
                return x * x;
            case 1:
                return x * x * x;
            case 2:
                return x * x * x * x;
            case 3:
                return x * x * x * x * x;
            case 4:
                return 1 - Math.cos(x * Math.PI / 2);
            case 5:
                return x == 0 ? 0 : Math.pow(2, 10 * x - 10);
            case 6:
                return 1 - Math.sqrt(1 - x * x);
            case 7: {
                double c1 = 1.70158;
                return (c1 + 1) * x * x * x - c1 * x * x;
            }
            case 8: {
                if (x == 0 || x == 1) {
                    return x;
                }
                double c4 = 2 * Math.PI / 3;
                return -Math.pow(2, 10 * x - 10) * Math.sin((x * 10 - 10.75) * c4);
            }
            default:
                return 1 - bounceOut(1 - x);
        }
    }

    private static double bounceOut(double x) {
        double n1 = 7.5625;
        double d1 = 2.75;
        if (x < 1 / d1) {
            return n1 * x * x;
        }
        if (x < 2 / d1) {
            x -= 1.5 / d1;
            return n1 * x * x + 0.75;
        }
        if (x < 2.5 / d1) {
            x -= 2.25 / d1;
            return n1 * x * x + 0.9375;
        }
        x -= 2.625 / d1;
        return n1 * x * x + 0.984375;
    }
}

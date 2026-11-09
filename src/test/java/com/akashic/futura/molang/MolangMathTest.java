package com.akashic.futura.molang;

import static com.akashic.futura.molang.MolangLanguageTest.num;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** {@code math.*}: upstream's functions and quirks, then the Bedrock superset. */
class MolangMathTest {

    private static final double EPS = 1e-4;

    @Test
    void trigonometryIsInDegrees() {
        assertEquals(1, num("math.sin(90)"), EPS);
        assertEquals(-1, num("math.cos(180)"), EPS);
        assertEquals(0, num("math.cos(90)"), EPS);
        assertEquals(0.5, num("math.sin(30)"), EPS);
        assertEquals(1, num("math.sin(q.t * 90)", new FakeContext().value("q.t", 1)), EPS);
        assertEquals(45, num("math.atan2(1, 1)"), EPS);
        assertEquals(90, num("math.acos(0)"), EPS);
        assertEquals(90, num("math.asin(1)"), EPS);
        assertEquals(45, num("math.atan(1)"), EPS);
        assertEquals((float) Math.PI, num("math.pi"));
    }

    @Test
    void upstreamFunctions() {
        assertEquals(3, num("math.abs(-3)"));
        assertEquals(3, num("math.clamp(5, 0, 3)"));
        assertEquals(0, num("math.clamp(-1, 0, 3)"));
        // min(max(v, lo), hi): with lo > hi the result is hi
        assertEquals(0, num("math.clamp(5, 3, 0)"));
        assertEquals(1, num("math.exp(0)"));
        assertEquals(-2, num("math.floor(-1.5)"));
        assertEquals(2.5, num("math.lerp(0, 10, 0.25)"));
        assertEquals(20, num("math.lerp(0, 10, 2)"));
        assertEquals(4, num("math.max(-1, 4)"));
        assertEquals(-1, num("math.min(-1, 4)"));
        assertEquals(1, num("math.mod(7, 3)"));
        // Java remainder: the sign follows the dividend
        assertEquals(-1, num("math.mod(-1, 3)"));
        assertEquals(1024, num("math.pow(2, 10)"));
        assertEquals(3, num("math.round(2.5)"));
        assertEquals(-2, num("math.round(-2.5)"));
        assertEquals(4, num("math.sqrt(16)"));
        assertEquals(-170, num("math.min_angle(190)"));
        assertEquals(-180, num("math.min_angle(180)"));
        assertEquals(-180, num("math.min_angle(-180)"));
        assertEquals(170, num("math.min_angle(-190)"));
        assertEquals(-180, num("math.min_angle(540)"));
    }

    @Test
    void failingMathFailsTheProgram() {
        // upstream's finite() throws: the whole program gives 0, ?? does not catch it
        assertEquals(0, num("math.sqrt(-1)"));
        assertEquals(0, num("math.sqrt(-1) ?? 3"));
        assertEquals(0, num("math.mod(5, 0) + 1"));
        assertEquals(0, num("math.ln(0) + 1"));
        assertEquals(0, num("math.exp(1000) + 1"));
        assertEquals(0, num("math.abs(1 / 0) + 1"));
        FakeContext context = new FakeContext().value("q.x", -4);
        assertEquals(0, num("v.y = 1; math.sqrt(q.x)", context));
        assertEquals(
            1,
            context.variables.get("y")
                .asNumber());
    }

    @Test
    void random() {
        FakeContext context = new FakeContext();
        Set<Double> integers = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            double r = num("math.random(1, 2)", context);
            assertTrue(r >= 1 && r < 2, "random " + r);
            double swapped = num("math.random(2, 1)", context);
            assertTrue(swapped >= 1 && swapped < 2, "swapped " + swapped);
            assertTrue(num("math.random(5, 5, 99)", context) == 5);
            double integer = num("math.random_integer(1, 3)", context);
            integers.add(integer);
            double reversed = num("math.random_integer(3.9, 1.2)", context);
            assertTrue(reversed == 1 || reversed == 2, "reversed " + reversed);
        }
        // upstream: the upper bound is exclusive
        assertEquals(new HashSet<>(java.util.Arrays.asList(1.0, 2.0)), integers);
        // and an empty range fails (nextInt(0) throws)
        assertEquals(0, num("math.random_integer(3, 3) + 1", context));
        assertEquals(3, num("math.die_roll(3, 1, 1)", context));
        assertEquals(6, num("math.die_roll_integer(3, 2, 2)", context));
        for (int i = 0; i < 200; i++) {
            double roll = num("math.die_roll_integer(2, 1, 3)", context);
            assertTrue(roll >= 2 && roll <= 6 && roll == Math.floor(roll), "roll " + roll);
        }
        // the rolls count against the loop budget
        assertEquals(0, num("math.die_roll(20000, 0, 1)", context));
    }

    @Test
    void bedrockSuperset() {
        assertEquals(2, num("math.ceil(1.2)"));
        assertEquals(-1, num("math.trunc(-1.7)"));
        assertEquals(1, num("math.trunc(1.7)"));
        assertEquals(0, num("math.ln(1)"));
        assertEquals(1, num("math.ln(math.exp(1))"), EPS);
        assertEquals(-1, num("math.sign(-2)"));
        assertEquals(1, num("math.sign(3)"));
        assertEquals(-1, num("math.sign(0)"));
        assertEquals(-3, num("math.copy_sign(3, -1)"));
        assertEquals(0.5, num("math.hermite_blend(0.5)"));
        assertEquals(1, num("math.hermite_blend(1)"));
        assertEquals(-180, num("math.lerprotate(170, -170, 0.5)"));
        assertEquals(15, num("math.lerprotate(10, 20, 0.5)"));
        assertEquals(0.25, num("math.inverse_lerp(0, 10, 2.5)"));
        assertEquals(0, num("math.inverse_lerp(1, 1, 5)"));
        assertEquals(2.5, num("math.ease_in_quad(0, 10, 0.5)"), EPS);
        assertEquals(7.5, num("math.ease_out_quad(0, 10, 0.5)"), EPS);
        assertEquals(0.5, num("math.ease_in_out_cubic(0, 1, 0.5)"), EPS);
        assertEquals(1, num("math.ease_out_bounce(0, 1, 1)"), EPS);
        assertEquals(0, num("math.ease_in_elastic(0, 1, 0)"), EPS);
        assertEquals(10, num("math.ease_in_out_back(0, 10, 1)"), EPS);
    }
}

package com.akashic.futura.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Syntax, precedence, values and control flow of the interpreter. */
class MolangLanguageTest {

    private static final double EPS = 1e-5;

    static double num(String source) {
        return num(source, new FakeContext());
    }

    static double num(String source, FakeContext context) {
        double viaNumber = Molang.parse(source)
            .evalNumber(context);
        return viaNumber;
    }

    static MolangValue value(String source, FakeContext context) {
        return Molang.parse(source)
            .evaluate(context);
    }

    static void assertFails(String source) {
        assertThrows(MolangSyntaxException.class, () -> Molang.compile(source), source);
        MolangExpression parsed = Molang.parse(source);
        assertTrue(parsed.isConstant(), source);
        assertEquals(0, parsed.evalNumber(new FakeContext()), source);
        assertEquals(MolangValue.ZERO, parsed.evaluate(new FakeContext()), source);
    }

    @Test
    void numbers() {
        assertEquals(1, num("1"));
        assertEquals(0.5, num(".5"));
        assertEquals(1, num("1."));
        assertEquals(150, num("1.5e2"));
        assertEquals(0.2, num("2E-1"), EPS);
        assertEquals(42, num("  42  "));
        assertEquals(0, num(""));
        assertEquals(0, num(" ; ; "));
        assertFails("1e");
        assertFails("1e40");
        assertFails("1.5.3");
    }

    @Test
    void booleansAndNull() {
        assertEquals(1, num("true"));
        assertEquals(0, num("false"));
        assertEquals(1, num("TRUE"));
        assertEquals(0, num("null"));
        assertEquals(3, num("null ?? 3"));
    }

    @Test
    void numbersAreThirtyTwoBitFloats() {
        // upstream computes in F32: 0.1f + 0.2f == 0.3f, unlike doubles
        assertEquals(1, num("0.1 + 0.2 == 0.3"));
        assertEquals((float) 0.1, num("0.1"));
        assertEquals((float) (1 / 3f), num("1 / 3"));
    }

    @Test
    void precedence() {
        assertEquals(7, num("1 + 2 * 3"));
        assertEquals(9, num("(1 + 2) * 3"));
        assertEquals(3, num("10 - 4 - 3"));
        assertEquals(1, num("8 / 4 / 2"));
        assertEquals(-6, num("-2 * 3"));
        assertEquals(6, num("-3 * -2"));
        assertEquals(-5, num("-(2 + 3)"));
        assertEquals(3, num("- - 3"));
        assertEquals(2, num("!0 + 1"));
        assertEquals(1, num("1 < 2 == 1"));
        assertEquals(1, num("1 + 1 == 2 && 3 > 2"));
        assertEquals(0, num("0 || 1 && 0"));
        assertEquals(1, num("1 || 0 && 0"));
        assertEquals(2, num("1 ? 2 : 3 + 4"));
        assertEquals(7, num("0 ? 2 : 3 + 4"));
        assertEquals(10, num("1 + 2 > 2 ? 10 : 20"));
        assertEquals(3, num("0 ? 1 : 0 ? 2 : 3"));
        assertEquals(6, num("1 ? 0 ? 5 : 6 : 7"));
        assertEquals(6, num("v.a ?? 5 + 1"));
        assertEquals(1, num("2 >= 2 && 2 <= 2 && 3 > 2 && 2 < 3"));
        assertEquals(1, num("1 != 2"));
    }

    @Test
    void comparisonsAndLogicGiveOneOrZero() {
        assertEquals(1, num("5 && 3"));
        assertEquals(0, num("5 && 0"));
        assertEquals(1, num("0 || -1"));
        assertEquals(0, num("!7"));
        // upstream: 2 == true is false (true is 1)
        assertEquals(0, num("2 == true"));
        assertEquals(1, num("1 == true"));
    }

    @Test
    void logicShortCircuits() {
        FakeContext context = new FakeContext().value("q.side", 1);
        num("0 && q.side", context);
        num("1 || q.side", context);
        num("0 ? q.side : 1", context);
        assertTrue(context.calls.isEmpty(), context.calls.toString());
        num("1 && q.side", context);
        assertEquals(1, context.calls.size());
    }

    @Test
    void namespacesAndAliases() {
        FakeContext context = new FakeContext().value("query.ground_speed", 3)
            .value("context.item", 4)
            .value("ysm.head_yaw", 5);
        assertEquals(3, num("q.ground_speed", context));
        assertEquals(3, num("query.ground_speed", context));
        assertEquals(3, num("Query.Ground_Speed", context));
        assertEquals(3, num("q.ground_speed()", context));
        assertEquals(4, num("c.item", context));
        assertEquals(4, num("context.item", context));
        assertEquals(5, num("ysm.head_yaw", context));
        context.calls.clear();
        num("ctrl.fly + fn.helper(1) + Foo.Bar + this + args.x + roaming.a.b", context);
        assertEquals("ctrl.fly[]", context.calls.get(0));
        assertEquals("fn.helper[1]", context.calls.get(1));
        assertEquals("foo.bar[]", context.calls.get(2));
        assertEquals("global.this[]", context.calls.get(3));
        assertEquals("args.x[]", context.calls.get(4));
        // roaming.a is a variable, roaming.a.b is not (upstream only maps two-part roaming names)
        assertEquals("roaming.a[]", context.calls.get(5));
    }

    @Test
    void persistentVariables() {
        FakeContext context = new FakeContext();
        MolangExpression counter = Molang.parse("v.count = v.count + 1; return v.count;");
        assertEquals(1, counter.evalNumber(context));
        assertEquals(2, counter.evalNumber(context));
        assertEquals(MolangValue.of(3), counter.evaluate(context));
        assertEquals(
            3,
            context.variables.get("count")
                .asNumber());

        assertEquals(2, num("variable.Speed = 2", context));
        assertEquals(
            2,
            context.variables.get("speed")
                .asNumber());
        assertEquals(2, num("v.speed", context));

        assertEquals(4, num("v.a = v.b = 4", context));
        assertEquals(
            4,
            context.variables.get("a")
                .asNumber());
        assertEquals(
            4,
            context.variables.get("b")
                .asNumber());

        // assigning a missing value makes the variable missing again
        assertTrue(context.variables.has("a"));
        num("v.a = q.unknown", context);
        assertFalse(context.variables.has("a"));
        assertEquals(9, num("v.a ?? 9", context));
    }

    @Test
    void tempVariablesLiveForOneEvaluation() {
        FakeContext context = new FakeContext();
        MolangExpression program = Molang.parse("t.x = (t.x ?? 0) + 1; return t.x;");
        assertEquals(1, program.evalNumber(context));
        assertEquals(1, program.evalNumber(context));
        assertEquals(MolangValue.ONE, program.evaluate(context));
        assertEquals(5, num("temp.y = 5; t.y", context));
        assertEquals(0, num("t.y", context));
        assertEquals("abc", value("t.s = 'abc'; t.s", context).asString());
        assertFalse(context.variables.has("y"));
    }

    @Test
    void structPathsAndVectors() {
        FakeContext context = new FakeContext().function("q.vec", args -> MolangValue.vector(1, 2, 3));
        assertEquals(3, num("v.a.b = 3; v.a.b", context));
        assertEquals(
            3,
            context.variables.get("a.b")
                .asNumber());
        assertEquals(3, num("(v.a).b", context));
        assertEquals(2, num("q.vec.y", context));
        assertEquals(0, num("q.vec.w", context));
        assertEquals(5, num("q.vec.w ?? 5", context));
        assertEquals(3, num("v.pos = q.vec; v.pos.z", context));
        assertEquals(1, num("t.p = q.vec; t.p.x", context));
        // a never-set child reads the parent's member; a set one wins
        assertEquals(7, num("v.pos.z = 7; v.pos.z", context));
        assertEquals(1, num("v.pos.x", context));
    }

    @Test
    void roamingVariables() {
        FakeContext context = new FakeContext();
        num("v.roaming.a = 2", context);
        assertEquals(
            2,
            context.variables.get("roaming.a")
                .asNumber());
        assertEquals(2, num("roaming.a", context));
        assertEquals(2, num("r.a", context));
        assertEquals(2, num("v.roaming.a", context));
        assertEquals(5, num("r.b = 5; v.roaming.b", context));
        assertFails("v.__ysm_roaming_a");
    }

    @Test
    void conditionalAndCoalesce() {
        FakeContext context = new FakeContext().value("q.known", 4)
            .function("q.nan", args -> MolangValue.of(Double.NaN));
        assertEquals(2, num("false ? 1 : 2"));
        assertEquals(5, num("1 ? 5"));
        // c ? a without ':' is missing when false
        assertEquals(0, num("0 ? 5"));
        assertEquals(9, num("(0 ? 5) ?? 9"));
        assertEquals(3, num("v.missing ?? 3", context));
        assertEquals(0, num("v.zero = 0; v.zero ?? 3", context));
        assertEquals(7, num("q.unknown ?? 7", context));
        assertEquals(4, num("q.known ?? 7", context));
        // a non-finite host number is missing
        assertEquals(5, num("q.nan ?? 5", context));
        // '' and false are values, not missing
        assertEquals("", value("'' ?? 1", context).asString());
        assertEquals(0, num("false ?? 1"));
        // right-associative
        assertEquals(8, num("v.n1 ?? v.n2 ?? 8", context));
    }

    @Test
    void returnAndStatements() {
        assertEquals(5, num("return 5; 6"));
        assertEquals(2, num("v.a = 1; return v.a + 1"));
        // without return: the value of the last statement
        assertEquals(3, num("1; 2; 3"));
        assertEquals(3, num("1; 2; 3;"));
        assertEquals(0, num("return;"));
        assertEquals(10, num("1 ? {return 10;} : 0; 20"));
        assertEquals(20, num("0 ? {return 10;} : 0; 20"));
        assertEquals("abc", value("return 'abc'", new FakeContext()).asString());
        assertEquals(1, num("return 'abc'"));
        FakeContext context = new FakeContext();
        assertEquals(0, num("{ v.a = 1; v.b = 2; }", context));
        assertEquals(
            1,
            context.variables.get("a")
                .asNumber());
        assertEquals(10, value("{ return 10; }; 3", context).asNumber());
    }

    @Test
    void loops() {
        assertEquals(10, num("t.i = 0; loop(10, { t.i = t.i + 1; }); return t.i;"));
        assertEquals(4, num("t.i = 0; loop(10, { t.i = t.i + 1; t.i >= 4 ? break; }); return t.i;"));
        assertEquals(
            18,
            num("t.s = 0; t.i = 0; loop(6, { t.i = t.i + 1; t.i == 3 ? continue; t.s = t.s + t.i; }); t.s"));
        assertEquals(3, num("t.n = 0; loop(3, { loop(5, { t.n = t.n + 1; break; }); }); return t.n;"));
        assertEquals(7, num("loop(5, { return 7; }); return 1;"));
        assertEquals(7, value("loop(5, { return 7; }); return 1;", new FakeContext()).asNumber());
        assertEquals(2, num("t.n = 0; loop(2.9, { t.n = t.n + 1; }); t.n"));
        assertEquals(0, num("t.n = 0; loop(-3, { t.n = t.n + 1; }); t.n"));
        assertEquals(3, num("v.k = 0; loop(3, v.k = v.k + 1); v.k"));
        assertFails("break");
        assertFails("loop(1)");
        assertFails("loop(1, 2, 3)");
    }

    @Test
    void loopBudgetFailsTheProgram() {
        FakeContext context = new FakeContext();
        // upstream allows 10000 loop iterations per evaluation; more fails the program, which gives 0
        assertEquals(10_000, num("t.n = 0; loop(10000, { t.n = t.n + 1; }); t.n", context));
        assertEquals(0, num("v.x = 5; loop(20000, {}); return 1", context));
        // writes before the failure stay
        assertEquals(
            5,
            context.variables.get("x")
                .asNumber());
    }

    @Test
    void forEachParsesAndDoesNothing() {
        FakeContext context = new FakeContext();
        assertEquals(1, num("for_each(t.x, q.list, { v.n = 1; }); 1", context));
        assertFalse(context.variables.has("n"));
        assertFails("for_each(v.x, q.list, {})");
        assertFails("for_each(t.x.y, q.list, {})");
        assertFails("for_each(t.x, q.list)");
    }

    @Test
    void strings() {
        FakeContext context = new FakeContext().value("q.name", "steve");
        assertEquals(1, num("'abc' == 'abc'"));
        assertEquals(0, num("'abc' == 'abd'"));
        assertEquals(1, num("'a' != 'b'"));
        assertEquals(1, num("q.name == 'steve'", context));
        assertEquals(0, num("q.name == 'Steve'", context));
        assertEquals(2, num("'' ? 1 : 2"));
        assertEquals(1, num("'x' ? 1 : 2"));
        // strings are never parsed as numbers
        assertEquals(1, num("'0' ? 1 : 2"));
        assertEquals(0, num("!'a'"));
        // a missing value equals the empty string
        assertEquals(1, num("q.unknown == ''", context));
        assertEquals(0, num("q.unknown != ''", context));
        // a non-empty string equals 1, not 2
        assertEquals(1, num("'abc' == 1"));
        assertEquals(0, num("'abc' == 2"));
        assertEquals("AbC", value("'AbC'", context).asString());
        assertEquals(MolangValue.Kind.STRING, value("'a b; c'", context).kind);
        assertFails("'unclosed");
        assertFails("\"double\"");
    }

    @Test
    void hostCallsWithArguments() {
        MolangValue[][] seen = new MolangValue[1][];
        FakeContext context = new FakeContext().function("ysm.second_order", args -> {
            seen[0] = args.clone();
            return MolangValue.of(args[1].asNumber() * 2);
        });
        assertEquals(6, num("ysm.second_order('hair', 1 + 2, 2, 0.4, 1)", context));
        assertEquals(5, seen[0].length);
        assertEquals("hair", seen[0][0].asString());
        assertEquals(3, seen[0][1].asNumber());
        assertEquals((float) 0.4, seen[0][3].asNumber());

        // missing arguments arrive as 0
        context.function("q.f", args -> args[0]);
        assertEquals(MolangValue.ZERO, value("q.f(v.none)", context));
        // a non-finite argument fails the program before the call
        context.calls.clear();
        assertEquals(0, num("q.f(1 / 0)", context));
        assertTrue(context.calls.isEmpty());
        // a host exception fails the program
        context.function("q.boom", args -> { throw new IllegalStateException("boom"); });
        assertEquals(0, num("v.before = 1; q.boom + 5", context));
        assertEquals(MolangValue.ZERO, value("q.boom", context));
        // left to right, once each
        context.calls.clear();
        context.value("q.a", 1)
            .value("q.b", 2);
        num("q.f(q.a, q.b)", context);
        assertEquals("query.a[]", context.calls.get(0));
        assertEquals("query.b[]", context.calls.get(1));
        assertEquals(3, context.calls.size());
    }

    @Test
    void arrow() {
        FakeContext other = new FakeContext().value("query.health", 20);
        FakeContext context = new FakeContext().value("query.target", "other")
            .value("query.nobody", "nobody")
            .value("query.health", 5);
        context.targets.put("other", other);
        assertEquals(20, num("q.target -> q.health", context));
        assertEquals(25, num("(q.target -> q.health) + q.health", context));
        assertEquals(0, num("q.nobody -> q.health", context));
        assertEquals(0, num("q.unknown -> q.health", context));
    }

    @Test
    void divisionQuirks() {
        FakeContext context = new FakeContext().value("q.zero", 0);
        // IEEE inside, but a non-finite program result is 0 (upstream fails the program)
        assertEquals(0, num("1 / 0"));
        assertEquals(MolangValue.ZERO, value("1 / 0", context));
        assertEquals(1, num("(1 / 0) > 5"));
        assertEquals(0, num("q.zero / q.zero", context));
        assertEquals(0, num("q.zero / q.zero + 7", context));
    }

    @Test
    void failSoft() {
        String[] garbage = { "1 +", ")(", "q.", "a & b", "a | b", "v.x(1)", "t.x(1)", "q.a.b()", "1 = 2", "math.pi = 3",
            "q.x = 1", "#", "{ 1 ", "1 2", "q.x(1,)", "(1)(2)", "v.", "1 ? 2 :", "v.break", "@foo", "q.x[1" };
        for (String source : garbage) {
            assertFails(source);
        }
        assertEquals(
            0,
            Molang.parse(null)
                .evalNumber(new FakeContext()));
        assertFalse(Molang.isValid("1 +"));
        assertTrue(Molang.isValid("1 + 1"));
    }

    @Test
    void unknownNamesAreMissing() {
        FakeContext context = new FakeContext();
        assertEquals(0, num("q.nothing + v.nothing + t.nothing + ysm.nothing", context));
        assertEquals(0, num("math.foo(1)", context));
        assertTrue(context.calls.contains("math.foo[1]"), context.calls.toString());
        // a built-in math function with the wrong arity is missing and its arguments never run
        context.calls.clear();
        assertEquals(0, num("math.sin(q.side, 2)", context));
        assertTrue(context.calls.isEmpty());
        assertEquals(4, num("math.sin(1, 2) ?? 4", context));
        assertEquals(4, num("math.abs ?? 4", context));
        assertEquals(0, num("q.x[0]", context));
    }

    @Test
    void comments() {
        assertEquals(3, num("1 + 2 // the rest is ignored"));
        assertEquals(5, num("v.a = 5; // set\nv.a"));
    }

    @Test
    void constantsAndCache() {
        assertTrue(
            Molang.parse("1 + 2 * 3")
                .isConstant());
        assertTrue(
            Molang.parse("math.sin(90) * 2 + math.pi")
                .isConstant());
        assertTrue(
            Molang.parse("'abc'")
                .isConstant());
        assertFalse(
            Molang.parse("math.random(0, 1)")
                .isConstant());
        assertFalse(
            Molang.parse("q.x + 1")
                .isConstant());
        assertFalse(
            Molang.parse("v.x = 1")
                .isConstant());
        assertEquals(
            2,
            Molang.parse("math.sin(90) * 2")
                .evalNumber(null));
        assertSame(Molang.parse("q.x * 2 + 1"), Molang.parse("q.x * 2 + 1"));
        assertNotSame(Molang.parse("q.x * 2 + 2"), Molang.parse("q.x * 2 + 3"));
    }

    @Test
    void reentrantAndConcurrentEvaluation() throws InterruptedException {
        MolangExpression program = Molang.parse("t.a = q.depth; loop(100, { t.a = t.a + 1; }); return t.a;");
        FakeContext context = new FakeContext();
        // a host function evaluating the same program again (another frame)
        context.function("q.depth", args -> MolangValue.of(context.calls.size() < 3 ? program.evalNumber(context) : 0));
        assertEquals(300, program.evalNumber(context));

        MolangExpression shared = Molang.parse("t.s = 0; loop(50, { t.s = t.s + q.step; }); t.s");
        Thread[] threads = new Thread[4];
        boolean[] ok = new boolean[threads.length];
        for (int i = 0; i < threads.length; i++) {
            int step = i + 1;
            int index = i;
            threads[i] = new Thread(() -> {
                FakeContext own = new FakeContext().value("q.step", step);
                boolean good = true;
                for (int k = 0; k < 2000; k++) {
                    own.calls.clear();
                    good &= shared.evalNumber(own) == 50 * step;
                }
                ok[index] = good;
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        for (boolean good : ok) {
            assertTrue(good);
        }
    }
}

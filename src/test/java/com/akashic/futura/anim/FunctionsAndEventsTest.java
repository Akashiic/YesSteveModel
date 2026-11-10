package com.akashic.futura.anim;

import static com.akashic.futura.anim.AnimTestSupport.EPS;
import static com.akashic.futura.anim.Tier2Support.animator;
import static com.akashic.futura.anim.Tier2Support.functions;
import static com.akashic.futura.anim.Tier2Support.run;
import static com.akashic.futura.anim.Tier2Support.up;
import static com.akashic.futura.anim.Tier2Support.var;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akashic.futura.molang.Molang;
import com.akashic.futura.molang.MolangValue;

class FunctionsAndEventsTest {

    private static final String CLIPS = up("idle", "Root", 1, true, 1) + ","
        + up("walk", "Root", 2, true, 1)
        + ","
        + up("custom", "Root", 9, true, 1);

    @Test
    void namesAndEventsOfFunctionFiles() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("helper", "return 1;");
        sources.put("physics_init@player_init", "v.a = 1;");
        sources.put("@player_ctrl_main", "return 3;");
        sources.put("other@player_init", "v.b = 1;");
        sources.put("bad@not_an_event", "v.c = 1;");
        sources.put("Mixed@SYNC", "v.d = 1;");
        ModelFunctions f = ModelFunctions.compile(sources);
        assertNotNull(f.function("helper"));
        assertNotNull(f.function("physics_init"));
        assertNotNull(f.function("mixed"));
        assertNotNull(f.function("bad"));
        // "@event" files are handlers only
        assertNull(f.function(""));
        assertEquals(
            2,
            f.handlers("player_init")
                .size());
        assertEquals(
            1,
            f.handlers("sync")
                .size());
        assertTrue(
            f.handlers("not_an_event")
                .isEmpty());
        assertNotNull(f.first("player_ctrl_main"));
        assertTrue(ModelFunctions.isEvent("player_ctrl_parallel_6"));
        assertTrue(!ModelFunctions.isEvent("fp.arm_ctrl_misc"));
        assertEquals("player_ctrl_pre_main", ModelFunctions.overrideEvent("player.pre_main"));
        // the arms keep defer and overrides only
        ModelFunctions arms = f.forFirstPerson();
        assertTrue(
            arms.handlers("player_init")
                .isEmpty());
        assertNotNull(arms.first("player_ctrl_main"));
    }

    @Test
    void userFunctionsTakeArgumentsAndReturn() {
        PlayerAnimator a = animator(
            CLIPS,
            null,
            functions(
                "add",
                "t.x = args[0] + args[1]; return t.x * 10;",
                "missing_arg",
                "return args[5] ?? 7;",
                "loop_forever",
                "return fn.loop_forever() + 1;",
                "caller@player_update",
                "t.x = 100; v.sum = fn.add(1, 2); v.missing = fn.missing_arg(1); v.deep = fn.loop_forever();"
                    + " v.local = t.x; v.unknown = fn.nope(1) ?? -1;"));
        run(a, new PlayerSnapshot(), 0, 0);
        assertEquals(30, var(a, "sum"), EPS);
        assertEquals(7, var(a, "missing"), EPS);
        // a function's t.* are its own
        assertEquals(100, var(a, "local"), EPS);
        assertEquals(-1, var(a, "unknown"), EPS);
        // recursion is cut off instead of overflowing the stack
        assertTrue(Double.isFinite(var(a, "deep")));
    }

    @Test
    void initRunsOnceAndUpdateEveryTick() {
        PlayerAnimator a = animator(
            CLIPS,
            null,
            functions(
                "count_init@player_init",
                "v.inits = v.inits + 1;",
                "count_update@player_update",
                "v.updates = v.updates + 1; v.flag = args[0];",
                "second@player_update",
                "v.after = v.updates;"));
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 9);
        // a frame at the same time is not a tick
        a.update(s, 9);
        assertEquals(1, var(a, "inits"), 0);
        assertEquals(10, var(a, "updates"), 0);
        assertEquals(1, var(a, "flag"), 0);
        // handlers run in file order
        assertEquals(10, var(a, "after"), 0);
    }

    @Test
    void codedControllerOverride() {
        PlayerAnimator a = animator(
            CLIPS,
            null,
            functions(
                "@player_ctrl_main",
                "v.mode == 1 ? { ctrl.set_animation('custom'); return ctrl.state_continue; };"
                    + " v.mode == 2 ? { return ctrl.state_stop; }; return 0;"));
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 5);
        // 0 delegates to the built-in predicate
        assertEquals(1, up(a, "Root"), EPS);
        a.variables()
            .set("mode", MolangValue.ONE);
        run(a, s, 6, 12);
        assertEquals(9, up(a, "Root"), EPS);
        a.variables()
            .set("mode", MolangValue.of(2.0));
        run(a, s, 13, 25);
        // stopped: back to rest
        assertEquals(0, up(a, "Root"), EPS);
    }

    @Test
    void syncDispatchesLocallyOrThroughTheSink() {
        String fns = "trigger@player_update";
        PlayerAnimator a = animator(
            CLIPS,
            null,
            functions(
                fns,
                "v.go ? { ysm.sync(4, 5); v.go = 0; };",
                "listener@sync",
                "v.got = args[0] * 10 + args[1]; v.calls = v.calls + 1;"));
        PlayerSnapshot s = new PlayerSnapshot();
        a.variables()
            .set("go", MolangValue.ONE);
        run(a, s, 0, 0);
        // no sink: the handlers run at once
        assertEquals(45, var(a, "got"), 0);
        List<float[]> sent = new ArrayList<>();
        a.setSyncSink(values -> sent.add(values));
        a.variables()
            .set("go", MolangValue.ONE);
        run(a, s, 1, 1);
        assertEquals(1, sent.size());
        assertEquals(1, var(a, "calls"), 0);
        // what the network relays back
        a.dispatchSync(new float[] { 7, 1 });
        assertEquals(71, var(a, "got"), 0);
        assertEquals(2, var(a, "calls"), 0);
        // a sink that declines dispatches locally
        a.setSyncSink(values -> false);
        a.variables()
            .set("go", MolangValue.ONE);
        run(a, s, 2, 2);
        assertEquals(3, var(a, "calls"), 0);
    }

    @Test
    void deferredCapturesRunNewestFirst() {
        PlayerAnimator a = animator(
            CLIPS,
            null,
            functions(
                "trigger@player_update",
                "v.go ? { ysm.defer(0.5, 1); ysm.defer(0.25, 2); v.go = 0; };",
                "late@defer",
                "v.trace = v.trace * 10 + args[1]; v.delay = args[0];"));
        a.variables()
            .set("go", MolangValue.ONE);
        run(a, new PlayerSnapshot(), 0, 0);
        assertEquals(21, var(a, "trace"), 0);
        assertEquals(0.5, var(a, "delay"), EPS);
    }

    @Test
    void instructionKeyframesRunAtTheirTimes() {
        String clips = "\"idle\":{\"loop\":true,\"animation_length\":1,"
            + "\"timeline\":{\"0.0\":\"v.t0 = v.t0 + 1;\",\"0.5\":[\"v.t5 = v.t5 + 1;\", \"v.when = q.anim_time;\"]},"
            + "\"bones\":{\"Root\":{\"position\":[0,1,0]}}}";
        PlayerAnimator a = animator(clips, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        // main blends in over 2 ticks (0.1 s), then 0.5 s = 10 ticks into the clip
        run(a, s, 0, 10);
        assertEquals(1, var(a, "t0"), 0);
        assertEquals(0, var(a, "t5"), 0);
        run(a, s, 11, 12);
        assertEquals(1, var(a, "t5"), 0);
        assertEquals(0.5, var(a, "when"), 0.051);
        // one loop later both ran again
        run(a, s, 13, 32);
        assertEquals(2, var(a, "t0"), 0);
        assertEquals(2, var(a, "t5"), 0);
    }

    @Test
    void mergedMultilineInstructionsShareTemporaries() {
        // merge_multiline_expr joins the lines of a timeline entry into one program
        Map<String, com.akashic.futura.format.pojo.animation.AnimationFile> sets = new LinkedHashMap<>();
        sets.put(
            "main",
            com.akashic.futura.format.FormatGson.create(true)
                .fromJson(
                    "{\"animations\":{\"idle\":{\"loop\":true,\"animation_length\":1,"
                        + "\"timeline\":{\"0.0\":[\"t.a = 3;\", \"v.merged = t.a * 2;\"]}}}}",
                    com.akashic.futura.format.pojo.animation.AnimationFile.class));
        AnimationLibrary lib = new AnimationLibrary(AnimationLibrary.compile(sets, new ClipCompiler()), null);
        PlayerAnimator a = new PlayerAnimator(ModelAnimations.of(Tier2Support.GEO, lib, ""));
        run(a, new PlayerSnapshot(), 0, 4);
        assertEquals(6, var(a, "merged"), 0);
        assertNotNull(Molang.parse("t.a = 3;\nv.merged = t.a * 2;\n"));
    }
}

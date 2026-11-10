package com.akashic.futura.anim;

import static com.akashic.futura.anim.AnimTestSupport.EPS;
import static com.akashic.futura.anim.Tier2Support.animator;
import static com.akashic.futura.anim.Tier2Support.run;
import static com.akashic.futura.anim.Tier2Support.up;
import static com.akashic.futura.anim.Tier2Support.var;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashic.futura.molang.MolangValue;

class BedrockControllerTest {

    private static final String CLIPS = up("idle", "Root", 1, true, 1) + ","
        + up("a", "Root", 10, true, 1)
        + ","
        + up("b", "Root", 20, true, 1)
        + ","
        + up("c", "Body", 4, true, 1)
        + ","
        + up("once", "Root", 30, false, 0.5f)
        + ",\"half\":{\"loop\":true,\"blend_weight\":0.5,\"bones\":{\"Root\":{\"position\":[0,8,0]}}}";

    private static HybridController main(PlayerAnimator a) {
        return (HybridController) a.controller("player.main");
    }

    @Test
    void declaredMainReplacesTheCodedPredicate() {
        PlayerAnimator a = animator(CLIPS, "\"player.main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}", null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 3);
        // the coded main would play idle (1 pixel); the model's controller plays a
        assertEquals(10, up(a, "Root"), EPS);
        HybridController main = main(a);
        assertNotNull(main.bedrock());
        assertSame(main.bedrock(), main.active());
        assertEquals("default", main.stateName());
    }

    @Test
    void withoutDeclarationTheCodedControllerPlays() {
        PlayerAnimator a = animator(CLIPS, "\"player.hold_mainhand\":{\"states\":{\"default\":{}}}", null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 5);
        assertEquals(1, up(a, "Root"), EPS);
        assertNull(main(a).bedrock());
    }

    @Test
    void transitionsInDeclarationOrderWithEntryAndExit() {
        String controllers = "\"player.main\":{\"initial_state\":\"start\",\"states\":{"
            + "\"start\":{\"animations\":[\"a\"],\"transitions\":[{\"second\":\"v.go >= 1\"},{\"third\":\"v.go >= 1\"}],"
            + "\"on_exit\":[\"v.exited = v.exited + 1\"]},"
            + "\"second\":{\"animations\":[\"b\"],\"on_entry\":[\"v.entered = v.entered + 1\", \"v.order = v.exited\"],"
            + "\"transitions\":[{\"start\":\"v.go == 0\"}]},"
            + "\"third\":{\"animations\":[\"c\"]}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 3);
        assertEquals("start", main(a).stateName());
        assertEquals(10, up(a, "Root"), EPS);
        a.variables()
            .set("go", MolangValue.of(1.0));
        run(a, s, 4, 6);
        // the first true transition wins
        assertEquals("second", main(a).stateName());
        assertEquals(20, up(a, "Root"), EPS);
        assertEquals(1, var(a, "entered"), EPS);
        assertEquals(1, var(a, "exited"), EPS);
        // on_exit of the old state runs before on_entry of the new one
        assertEquals(1, var(a, "order"), EPS);
        run(a, s, 7, 20);
        // staying in a state runs nothing again
        assertEquals(1, var(a, "entered"), EPS);
        a.variables()
            .set("go", MolangValue.of(0.0));
        run(a, s, 21, 22);
        assertEquals("start", main(a).stateName());
        // second has no on_exit; start's ran once
        assertEquals(1, var(a, "exited"), 0);
        assertEquals(10, up(a, "Root"), EPS);
    }

    @Test
    void emptyStatesAreLeftInTheSameFrameWithoutLooping() {
        String controllers = "\"player.main\":{\"states\":{" + "\"default\":{\"transitions\":[{\"hop\":\"1\"}]},"
            + "\"hop\":{\"transitions\":[{\"default\":\"1\"},{\"end\":\"v.done\"}]},"
            + "\"end\":{\"animations\":[\"b\"]}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        PlayerSnapshot s = new PlayerSnapshot();
        // default -> hop -> default would loop: a state is visited once per frame, so the frame ends in hop
        run(a, s, 0, 0);
        assertEquals("hop", main(a).stateName());
        a.variables()
            .set("done", MolangValue.ONE);
        run(a, s, 1, 1);
        // hop's first transition (back to default) holds, then default -> hop is refused; still hop
        assertEquals("hop", main(a).stateName());
    }

    @Test
    void emptyStateChainsToAPlayingStateInOneFrame() {
        String controllers = "\"player.main\":{\"states\":{" + "\"default\":{\"transitions\":[{\"hop\":\"1\"}]},"
            + "\"hop\":{\"transitions\":[{\"end\":\"1\"}]},"
            + "\"end\":{\"animations\":[\"b\"]}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        run(a, new PlayerSnapshot(), 0, 0);
        assertEquals("end", main(a).stateName());
        assertEquals(20, up(a, "Root"), EPS);
    }

    @Test
    void animationsOfAStateAreSummedByBlendWeightAndCondition() {
        String controllers = "\"player.main\":{\"states\":{\"default\":{\"animations\":"
            + "[\"a\", \"half\", {\"b\": \"v.with_b\"}, \"c\"]}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 2);
        // a (10) + half (8 x 0.5); b is off; c moves another bone
        assertEquals(14, up(a, "Root"), EPS);
        assertEquals(4, up(a, "Body"), EPS);
        a.variables()
            .set("with_b", MolangValue.ONE);
        run(a, s, 3, 4);
        assertEquals(34, up(a, "Root"), EPS);
    }

    @Test
    void builtinStateHandsBackToTheCodedPredicate() {
        String controllers = "\"player.main\":{\"states\":{"
            + "\"default\":{\"animations\":[\"a\"],\"transitions\":[{\"ysm-builtin\":\"v.coded\"}]},"
            + "\"ysm-builtin\":{\"transitions\":[{\"default\":\"!v.coded\"}],\"blend_transition\":0}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 3);
        assertEquals(10, up(a, "Root"), EPS);
        a.variables()
            .set("coded", MolangValue.ONE);
        run(a, s, 4, 8);
        // the coded main predicate selects idle, with the state's (zero) blend
        assertSame(main(a).coded(), main(a).active());
        assertTrue(
            main(a).stateName()
                .startsWith("[builtin] Coded -> idle"),
            main(a).stateName());
        assertEquals(1, up(a, "Root"), EPS);
        a.variables()
            .set("coded", MolangValue.ZERO);
        run(a, s, 9, 10);
        assertSame(main(a).bedrock(), main(a).active());
        assertEquals(10, up(a, "Root"), EPS);
    }

    @Test
    void finishedAnimationsDriveTransitions() {
        String controllers = "\"player.main\":{\"states\":{"
            + "\"default\":{\"animations\":[\"once\"],\"transitions\":[{\"after\":\"q.all_animations_finished\"}]},"
            + "\"after\":{\"animations\":[\"b\"]}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 5);
        assertEquals("default", main(a).stateName());
        assertEquals(30, up(a, "Root"), EPS);
        // 0.5 s = 10 ticks, ends one frame late
        run(a, s, 6, 13);
        assertEquals("after", main(a).stateName());
    }

    @Test
    void blendTransitionCurveBlendsFromThePreviousPose() {
        String controllers = "\"player.main\":{\"states\":{"
            + "\"default\":{\"animations\":[\"a\"],\"transitions\":[{\"next\":\"v.go\"}]},"
            + "\"next\":{\"animations\":[\"b\"],\"blend_transition\":{\"0.0\":1,\"0.25\":0.5,\"0.5\":0}}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 3);
        a.variables()
            .set("go", MolangValue.ONE);
        run(a, s, 4, 4);
        // tick 0 of the transition: the old pose
        assertEquals(10, up(a, "Root"), EPS);
        run(a, s, 5, 9);
        // 5 ticks = 0.25 s: halfway (weight of the old pose 0.5)
        assertEquals(15, up(a, "Root"), EPS);
        run(a, s, 10, 20);
        assertEquals(20, up(a, "Root"), EPS);
    }

    @Test
    void subEntryStateRunsTheNestedController() {
        String controllers = "\"player.main\":{\"states\":{"
            + "\"default\":{\"transitions\":[{\"ysm-entry-walk\":\"1\"}]},"
            + "\"ysm-entry-walk\":{}}},"
            + "\"player.main.walk\":{\"states\":{\"default\":{\"animations\":[\"b\"],\"on_entry\":[\"v.walk=1\"],"
            + "\"on_exit\":[\"v.walk=2\"]}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 2);
        assertEquals("[walk] default", main(a).stateName());
        assertEquals(20, up(a, "Root"), EPS);
        assertEquals(1, var(a, "walk"), 0);
    }

    @Test
    void stateSoundsPlayOnEntryAndStopOnTheNextTransition() {
        String controllers = "\"player.main\":{\"states\":{"
            + "\"default\":{\"animations\":[\"a\"],\"sound_effects\":[{\"effect\":\"step\"}],"
            + "\"transitions\":[{\"next\":\"v.go\"}]},"
            + "\"next\":{\"animations\":[\"b\"],\"on_entry\":[\"ysm.play_sound('bell', 'bell')\"]}}}";
        PlayerAnimator a = animator(CLIPS, controllers, null);
        Tier2Support.Sounds sounds = new Tier2Support.Sounds();
        a.setSoundBackend(sounds);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 3);
        assertEquals(java.util.Arrays.asList("step"), sounds.played);
        assertTrue(sounds.isPlaying("step"));
        a.variables()
            .set("go", MolangValue.ONE);
        run(a, s, 4, 5);
        assertFalse(sounds.isPlaying("step"));
        // on_entry plays in the controller's scope
        assertTrue(sounds.isPlaying("bell"));
    }

    @Test
    void optionalControllersExistOnlyWhenDeclared() {
        List<String> plain = names(animator(CLIPS, null, null));
        assertFalse(plain.contains("player.pre_main"));
        String controllers = "\"player.post_hold\":{\"states\":{\"default\":{}}},"
            + "\"player.pre_main_extra\":{\"states\":{\"default\":{}}},"
            + "\"player.pre_main\":{\"states\":{\"default\":{}}},"
            + "\"player.parallel_3\":{\"states\":{\"default\":{\"animations\":[\"c\"]}}}";
        List<String> names = names(animator(CLIPS, controllers, ModelFunctions.EMPTY));
        int main = names.indexOf("player.main");
        assertEquals(main - 2, names.indexOf("player.pre_main"));
        assertEquals(main - 1, names.indexOf("player.pre_main_extra"));
        assertEquals(names.indexOf("player.hold_mainhand") + 1, names.indexOf("player.post_hold"));
        assertTrue(names.indexOf("player.parallel_3") > names.indexOf("player.cap"));
        // an override function also declares one
        List<String> byFunction = names(
            animator(CLIPS, null, Tier2Support.functions("@player_ctrl_post_use", "return ctrl.state_stop;")));
        assertEquals(byFunction.indexOf("player.use") + 1, byFunction.indexOf("player.post_use"));
    }

    @Test
    void firstPersonMiscAndParallelFromFiles() {
        String controllers = "\"fp.arm.misc\":{\"states\":{\"default\":{\"animations\":[\"c\"]}}},"
            + "\"fp.arm.parallel_2\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}";
        ModelAnimations body = ModelAnimations.of(Tier2Support.GEO, Tier2Support.library(CLIPS), "");
        ModelAnimations arms = ModelAnimations.firstPersonOf(
            Tier2Support.GEO,
            Tier2Support.library(CLIPS),
            "",
            Tier2Support.controllers(controllers),
            null);
        PlayerAnimator arm = PlayerAnimator.firstPerson(arms, new PlayerAnimator(body));
        List<String> names = names(arm);
        assertEquals("fp.arm.misc", names.get(0));
        assertEquals("fp.arm.parallel_2", names.get(1));
        run(arm, new PlayerSnapshot(), 0, 2);
        assertEquals(4, up(arm, "Body"), EPS);
        assertEquals(10, up(arm, "Root"), EPS);
    }

    @Test
    void blendCurves() {
        BlendCurve linear = BlendCurve.linear(0.5f);
        assertEquals(10, linear.length(), EPS);
        assertEquals(0.5f, linear.progress(5), EPS);
        BlendCurve curve = BlendCurve.segmented(new float[] { 0, 0.1f, 0.2f }, new float[] { 1, 0.25f, 0 });
        assertEquals(4, curve.length(), EPS);
        assertEquals(0, curve.progress(0), EPS);
        assertEquals(0.375f, curve.progress(1), EPS);
        assertEquals(0.75f, curve.progress(2), EPS);
        assertEquals(0.875f, curve.progress(3), EPS);
        assertEquals(1, curve.progress(9), EPS);
        assertEquals(0, BlendCurve.NONE.length(), 0);
    }

    private static List<String> names(PlayerAnimator a) {
        List<String> names = new ArrayList<>();
        for (AnimationController c : a.controllers()) {
            names.add(c.name());
        }
        return names;
    }
}

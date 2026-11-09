package com.akashic.futura.anim;

import static com.akashic.futura.anim.AnimTestSupport.DEG;
import static com.akashic.futura.anim.AnimTestSupport.EPS;
import static com.akashic.futura.anim.AnimTestSupport.geometry;
import static com.akashic.futura.anim.AnimTestSupport.library;
import static com.akashic.futura.anim.AnimTestSupport.play;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.akashic.futura.anim.AnimTestSupport.Rig;

/** Loop modes, transitions, return to rest and blending through the processor. */
class PlaybackTest {

    private static final String RAMP = "{\"0.0\":[0,0,0],\"1.0\":[20,20,20]}";

    private static Rig rig(String animations) {
        return new Rig(geometry("a", "b"), library(animations, null));
    }

    @Test
    void loopWrapsAround() {
        Rig r = rig("\"x\":{\"loop\":true,\"bones\":{\"a\":{\"position\":" + RAMP + "}}}");
        r.add("c", 0, false, play("x"));
        r.tick(0);
        assertEquals(0, r.value("a", 1, 0), EPS);
        r.tick(10);
        assertEquals(10, r.value("a", 1, 0), EPS);
        r.tick(25);
        assertEquals(5, r.value("a", 1, 0), EPS);
        r.tick(47);
        assertEquals(7, r.value("a", 1, 0), EPS);
    }

    @Test
    void holdOnLastFrameStaysAtTheEnd() {
        Rig r = rig("\"x\":{\"loop\":\"hold_on_last_frame\",\"bones\":{\"a\":{\"position\":" + RAMP + "}}}");
        CodedController c = r.add("c", 0, false, play("x"));
        r.tick(0);
        r.tick(15);
        assertEquals(15, r.value("a", 1, 0), EPS);
        r.tick(100);
        assertEquals(20, r.value("a", 1, 0), EPS);
        r.tick(500);
        assertEquals(20, r.value("a", 1, 0), EPS);
        assertEquals(PlaybackState.RUNNING, c.state());
    }

    @Test
    void playOnceBlendsOutThenRests() {
        Rig r = rig("\"x\":{\"bones\":{\"a\":{\"position\":" + RAMP + "}}}");
        CodedController c = r.add("c", 0, false, play("x"));
        r.tick(0);
        r.tick(19);
        assertEquals(19, r.value("a", 1, 0), EPS);
        // the clip ends: the ending transition starts from the last sampled value
        r.tick(20);
        assertEquals(PlaybackState.ENDING_TRANSITION, c.state());
        assertEquals(19, r.value("a", 1, 0), EPS);
        // halfway through the 3-tick ending, halfway to what lies below (nothing: 0)
        r.tick(21.5f);
        assertEquals(9.5f, r.value("a", 1, 0), EPS);
        // the same request does not restart it: idle, and the bone eases to rest
        r.tick(23);
        assertEquals(PlaybackState.IDLE, c.state());
        r.tick(40);
        assertEquals(0, r.value("a", 1, 0), EPS);
        assertEquals(0, r.processor.activeBones());
    }

    @Test
    void reselectingTheSameAnimationDoesNotRestartIt() {
        Rig r = rig(
            "\"x\":{\"loop\":true,\"animation_length\":100,\"bones\":{\"a\":{\"position\":"
                + "{\"0.0\":[0,0,0],\"5.0\":[100,100,100]}}}}");
        CodedController c = r.add("c", 0, false, play("x"));
        for (int t = 0; t <= 30; t++) {
            r.tick(t);
        }
        assertEquals(30, r.value("a", 1, 0), EPS);
        // an explicit reload restarts
        c.indicateReload();
        r.tick(31);
        r.tick(32);
        assertEquals(1, r.value("a", 1, 0), EPS);
    }

    @Test
    void beginningTransitionBlendsFromTheCurrentPose() {
        Rig r = rig("\"x\":{\"loop\":true,\"bones\":{\"a\":{\"position\":[8,8,8],\"rotation\":[0,0,90]}}}");
        r.add("main", 0.1f, false, play("x")); // 2 ticks
        r.tick(0);
        assertEquals(0, r.value("a", 1, 0), EPS);
        r.tick(1);
        assertEquals(4, r.value("a", 1, 0), EPS);
        // rotation goes through quaternion nlerp: halfway between 0 and 90 degrees about z is 45
        assertEquals(45 * DEG, r.value("a", 0, 2), EPS);
        r.tick(2);
        assertEquals(8, r.value("a", 1, 0), EPS);
        assertEquals(90 * DEG, r.value("a", 0, 2), EPS);
    }

    @Test
    void switchingAnimationsBlendsFromWhereTheBoneWas() {
        String[] current = { "x" };
        Rig r = rig(
            "\"x\":{\"loop\":true,\"bones\":{\"a\":{\"position\":[10,0,0]}}},"
                + "\"y\":{\"loop\":true,\"bones\":{\"a\":{\"position\":[20,0,0]}}}");
        r.add("main", 0.1f, false, c -> {
            c.setAnimation(current[0]);
            return PlayState.CONTINUE;
        });
        for (int t = 0; t <= 5; t++) {
            r.tick(t);
        }
        assertEquals(10, r.value("a", 1, 0), EPS);
        current[0] = "y";
        r.tick(6);
        assertEquals(10, r.value("a", 1, 0), EPS);
        r.tick(7);
        assertEquals(15, r.value("a", 1, 0), EPS);
        r.tick(8);
        assertEquals(20, r.value("a", 1, 0), EPS);
    }

    @Test
    void aBoneNoLongerAnimatedEasesBackToRestOverThreeTicks() {
        String[] current = { "x" };
        Rig r = rig(
            "\"x\":{\"loop\":true,\"bones\":{\"a\":{\"position\":[12,0,0],\"scale\":[2,2,2]}}},"
                + "\"y\":{\"loop\":true,\"bones\":{\"b\":{\"position\":[1,0,0]}}}");
        r.add("c", 0, false, c -> {
            c.setAnimation(current[0]);
            return PlayState.CONTINUE;
        });
        r.tick(0);
        r.tick(1);
        assertEquals(12, r.value("a", 1, 0), EPS);
        assertEquals(2, r.value("a", 2, 0), EPS);
        current[0] = "y";
        r.tick(2); // one tick after the last update: 1/3 of the way
        assertEquals(8, r.value("a", 1, 0), EPS);
        r.tick(3); // offset stays the value when the easing began
        assertEquals(4, r.value("a", 1, 0), EPS);
        assertEquals(4 / 3f, r.value("a", 2, 0), EPS);
        r.tick(4);
        assertEquals(0, r.value("a", 1, 0), EPS);
        assertEquals(1, r.value("a", 2, 0), EPS);
        assertEquals(1, r.value("b", 1, 0), EPS);
    }

    @Test
    void laterControllersOverwrite() {
        Rig r = rig(
            "\"x\":{\"loop\":true,\"bones\":{\"a\":{\"rotation\":[0,0,10],\"position\":[3,0,0]}}},"
                + "\"y\":{\"loop\":true,\"bones\":{\"a\":{\"rotation\":[0,0,5]}}}");
        r.add("first", 0, false, play("x"));
        r.add("second", 0, false, play("y"));
        r.tick(0);
        assertEquals(5 * DEG, r.value("a", 0, 2), EPS);
        // channels the second leaves alone keep the first's value
        assertEquals(3, r.value("a", 1, 0), EPS);
    }

    @Test
    void parallelControllersAddRotation() {
        Rig r = rig(
            "\"x\":{\"loop\":true,\"bones\":{\"a\":{\"rotation\":[0,0,10],\"position\":[3,0,0]}}},"
                + "\"p\":{\"loop\":true,\"bones\":{\"a\":{\"rotation\":[0,0,5],\"position\":[1,0,0]},"
                + "\"b\":{\"rotation\":[0,0,7]}}}");
        r.add("main", 0, false, play("x"));
        r.add("parallel_0", 0, true, play("p"));
        r.add("parallel_1", 0, true, play("p"));
        r.tick(0);
        // rotation adds: 10 + 5 + 5
        assertEquals(20 * DEG, r.value("a", 0, 2), EPS);
        // position still overwrites
        assertEquals(1, r.value("a", 1, 0), EPS);
        // a bone only the parallel layers touch: their sum
        assertEquals(14 * DEG, r.value("b", 0, 2), EPS);
        // every frame starts again from the base
        r.tick(1);
        assertEquals(20 * DEG, r.value("a", 0, 2), EPS);
    }

    @Test
    void blendWeightScalesTheClip() {
        Rig r = rig(
            "\"x\":{\"loop\":true,\"blend_weight\":0.5,\"bones\":{\"a\":{\"position\":[8,0,0],"
                + "\"scale\":[3,3,3],\"rotation\":[0,0,40]}}}");
        r.add("c", 0, false, play("x"));
        r.tick(0);
        assertEquals(4, r.value("a", 1, 0), EPS);
        assertEquals(2, r.value("a", 2, 0), EPS); // 1 + (3 - 1) * 0.5
        assertEquals(20 * DEG, r.value("a", 0, 2), EPS);
    }

    @Test
    void stopBlendsOutAndPauseContributesNothing() {
        PlayState[] state = { PlayState.CONTINUE };
        Rig r = rig(
            "\"x\":{\"loop\":true,\"bones\":{\"a\":{\"position\":[9,0,0]}}},"
                + "\"base\":{\"loop\":true,\"bones\":{\"a\":{\"position\":[3,0,0]}}}");
        r.add("base", 0, false, play("base"));
        CodedController top = r.add("top", 0, false, c -> {
            if (state[0] != PlayState.STOP) {
                c.setAnimation("x");
            }
            return state[0];
        });
        r.tick(0);
        assertEquals(9, r.value("a", 1, 0), EPS);
        state[0] = PlayState.PAUSE;
        r.tick(1);
        assertEquals(3, r.value("a", 1, 0), EPS);
        state[0] = PlayState.CONTINUE;
        r.tick(2);
        assertEquals(9, r.value("a", 1, 0), EPS);
        state[0] = PlayState.STOP;
        r.tick(3); // stop: the ending transition starts, last frame's point is sampled once more
        assertEquals(PlaybackState.ENDING_TRANSITION, top.state());
        assertEquals(9, r.value("a", 1, 0), EPS);
        r.tick(4.5f); // halfway: between 9 and the base below
        assertEquals(6, r.value("a", 1, 0), EPS);
        r.tick(6);
        r.tick(7);
        assertSame(PlaybackState.IDLE, top.state());
        assertEquals(3, r.value("a", 1, 0), EPS);
    }

    @Test
    void channelsOfBonesTheGeometryLacksAreSkipped() {
        Rig r = rig(
            "\"x\":{\"loop\":true,\"bones\":{\"molang\":{\"position\":[\"query.anim_time\",0,0]},"
                + "\"a\":{\"position\":[2,0,0]}}}");
        r.add("c", 0, false, play("x"));
        r.tick(0);
        assertEquals(2, r.value("a", 1, 0), EPS);
        assertEquals(1, r.processor.activeBones());
    }

    @Test
    void animTimeIsTheClipTimeWhileSampling() {
        Rig r = rig(
            "\"x\":{\"loop\":true,\"animation_length\":0.5,\"bones\":{\"a\":{\"position\":"
                + "[\"query.anim_time\",0,0]}}}");
        r.add("c", 0, false, play("x"));
        r.tick(100); // the clip starts here
        r.tick(104);
        assertEquals(0.2f, r.value("a", 1, 0), EPS);
        r.tick(113); // looped: 13 % 10 = 3 ticks
        assertEquals(0.15f, r.value("a", 1, 0), EPS);
    }
}

package com.akashic.futura.anim;

import static com.akashic.futura.anim.AnimTestSupport.DEG;
import static com.akashic.futura.anim.AnimTestSupport.EPS;
import static com.akashic.futura.anim.AnimTestSupport.clips;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akashic.futura.format.pojo.animation.LoopType;

/** Keyframe sampling, unit conversions and clip metadata, straight on the compiled channels. */
class KeyframeSamplingTest {

    private final float[] out = new float[3];
    private final float[] scratch = new float[12];
    private final AnimTestSupport.TestContext ctx = new AnimTestSupport.TestContext();

    private Channel position(String channelJson) {
        Map<String, AnimationClip> clips = clips("\"a\":{\"bones\":{\"b\":{\"position\":" + channelJson + "}}}");
        return clips.get("a").tracks[0].position;
    }

    private Channel rotation(String channelJson) {
        Map<String, AnimationClip> clips = clips("\"a\":{\"bones\":{\"b\":{\"rotation\":" + channelJson + "}}}");
        return clips.get("a").tracks[0].rotation;
    }

    private float[] at(Channel channel, float tick) {
        channel.sample(ctx, tick, out, 0, scratch);
        return out;
    }

    private static void assertVec(float x, float y, float z, float[] v) {
        assertEquals(x, v[0], EPS, "x");
        assertEquals(y, v[1], EPS, "y");
        assertEquals(z, v[2], EPS, "z");
    }

    @Test
    void linearInterpolatesBetweenKeyframes() {
        Channel c = position("{\"0.0\":[0,0,0],\"1.0\":[10,20,30]}");
        assertVec(0, 0, 0, at(c, 0));
        assertVec(5, 10, 15, at(c, 10));
        assertVec(10, 20, 30, at(c, 20));
        // after the last keyframe the last value holds
        assertVec(10, 20, 30, at(c, 35));
    }

    @Test
    void beforeTheFirstKeyframeItsPreValueHolds() {
        Channel c = position("{\"0.5\":{\"pre\":[4,4,4],\"post\":[6,6,6]},\"1.0\":[6,6,6]}");
        assertVec(4, 4, 4, at(c, 0));
        assertVec(4, 4, 4, at(c, 9.9f));
        // at the keyframe the post value takes over
        assertVec(6, 6, 6, at(c, 10));
    }

    @Test
    void preAndPostMakeAStep() {
        Channel c = position("{\"0.0\":[0,0,0],\"1.0\":{\"pre\":[1,1,1],\"post\":[5,5,5]},\"2.0\":[5,5,5]}");
        assertVec(0.5f, 0.5f, 0.5f, at(c, 10));
        float[] v = at(c, 19.9f);
        assertEquals(0.995f, v[0], 1e-3f);
        assertVec(5, 5, 5, at(c, 20));
        assertVec(5, 5, 5, at(c, 30));
    }

    @Test
    void catmullRomUsesTheNeighbours() {
        Channel c = position(
            "{\"0.0\":{\"post\":[0,0,0],\"lerp_mode\":\"catmullrom\"},\"1.0\":{\"post\":[10,10,10],"
                + "\"lerp_mode\":\"catmullrom\"},\"2.0\":{\"post\":[0,0,0],\"lerp_mode\":\"catmullrom\"},"
                + "\"3.0\":{\"post\":[10,10,10],\"lerp_mode\":\"catmullrom\"}}");
        // segment 1s..2s: left 0, begin 10, end 0, right 10
        float expected = AnimMath.catmullRom(0.5f, 0, 10, 0, 10);
        assertEquals(5f, expected, EPS);
        float[] v = at(c, 30);
        assertEquals(expected, v[0], EPS);
        float quarter = AnimMath.catmullRom(0.25f, 0, 10, 0, 10);
        assertEquals(quarter, at(c, 25)[0], EPS);
        // hand check of the cubic: v0 = (0-0)/2 = 0, v1 = (10-10)/2 = 0 -> 10 + (-30)t^2 + 20t^3 at t = 0.25
        assertEquals(10 - 30 * 0.0625f + 20 * 0.015625f, quarter, EPS);
        // the first segment uses the first keyframe as its own left neighbour
        assertEquals(AnimMath.catmullRom(0.5f, 0, 0, 10, 0), at(c, 10)[0], EPS);
    }

    @Test
    void aSegmentEasesCatmullRomWhenItsBeginKeyframeDoes() {
        // Blockbench's rule: begin catmullrom + end linear -> catmullrom
        Channel c = position(
            "{\"0.0\":[0,0,0],\"1.0\":{\"post\":[10,10,10],\"lerp_mode\":\"catmullrom\"},\"2.0\":[0,0,0],"
                + "\"3.0\":[0,0,0]}");
        assertEquals(AnimMath.catmullRom(0.5f, 0, 10, 0, 0), at(c, 30)[0], EPS);
        // and the segment into it (end catmullrom) too
        assertEquals(AnimMath.catmullRom(0.5f, 0, 0, 10, 0), at(c, 10)[0], EPS);
        // but a segment between two linear keyframes stays linear
        assertEquals(0, at(c, 50)[0], EPS);
    }

    @Test
    void aScalarAppliesToEveryAxis() {
        assertVec(3, 3, 3, at(position("3"), 0));
        assertVec(2, 2, 2, at(position("[2]"), 0));
        // rotation: x and y negated, z kept
        assertVec(-90 * DEG, -90 * DEG, 90 * DEG, at(rotation("90"), 0));
    }

    @Test
    void rotationBecomesRadiansWithXAndYNegated() {
        assertVec(-10 * DEG, -20 * DEG, 30 * DEG, at(rotation("[10,20,30]"), 0));
        assertVec(-10 * DEG, -20 * DEG, 30 * DEG, at(rotation("{\"0.0\":[0,0,0],\"1.0\":[20,40,60]}"), 10));
    }

    @Test
    void positionStaysInPixels() {
        assertVec(16, 8, -16, at(position("[16,8,-16]"), 0));
    }

    @Test
    void expressionChannelsAreEvaluatedEveryTime() {
        Channel c = position("[\"query.anim_time\",1,\"query.anim_time\"]");
        ctx.animTime = 0.5f;
        assertVec(0.5f, 1, 0.5f, at(c, 0));
        ctx.animTime = 2f;
        assertVec(2, 1, 2, at(c, 0));
        // a constant expression is folded at compile time
        Channel k = position("[\"7\",\"8\",\"9\"]");
        assertTrue(k.segments[0].begin.isConstant());
        assertVec(7, 8, 9, at(k, 0));
    }

    @Test
    void clipMetadata() {
        Map<String, AnimationClip> clips = clips(
            "\"once\":{\"animation_length\":2,\"bones\":{\"b\":{\"position\":[1,1,1]}}},"
                + "\"loop\":{\"loop\":true,\"bones\":{\"b\":{\"position\":{\"0.0\":[0,0,0],\"1.5\":[1,1,1]}}}},"
                + "\"hold\":{\"loop\":\"hold_on_last_frame\",\"blend_weight\":0.5,\"bones\":{}},"
                + "\"empty\":{\"loop\":true},"
                + "\"timeline\":{\"timeline\":{\"0.5\":\"v.x = 1;\"}}");
        assertEquals(40, clips.get("once").length, EPS);
        assertEquals(LoopType.PLAY_ONCE, clips.get("once").loop);
        // no animation_length: the last keyframe
        assertEquals(30, clips.get("loop").length, EPS);
        assertEquals(LoopType.LOOP, clips.get("loop").loop);
        assertEquals(LoopType.HOLD_ON_LAST_FRAME, clips.get("hold").loop);
        assertEquals(0.5f, clips.get("hold").blendWeight.eval(ctx), EPS);
        assertNull(clips.get("once").blendWeight);
        assertTrue(
            clips.get("empty")
                .isEmpty());
        // a timeline alone is not empty
        assertTrue(
            !clips.get("timeline")
                .isEmpty());
        assertEquals(10, clips.get("timeline").instructions[0].tick, EPS);
        // no keyframes after 0 and no length: never ends
        assertEquals(Float.MAX_VALUE, clips.get("hold").length, 0);
    }
}

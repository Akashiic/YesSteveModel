/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/bone/ (BoneKeyFrame, TransitionKeyFrame, LinearKeyFrame,
 * CatmullRomKeyFrame, BoneKeyFrameProcessor, EasingType) and
 * src/main/java/com/elfmcys/ysm/geckolib3/util/OrderedSegmentSearcher.java, Apache License 2.0.
 * Changes: segments sample into a caller array; the stateful cursor of OrderedSegmentSearcher is replaced by a binary
 * search that returns the same segment values.
 */
package com.akashic.futura.anim;

import java.util.List;

import com.akashic.futura.molang.MolangContext;

/**
 * One channel (rotation, position or scale) of one bone in one clip: the keyframes as consecutive segments. Segment 0
 * runs from tick 0 to the first keyframe and holds its {@code pre} value; segment {@code i} runs from keyframe
 * {@code i-1} to keyframe {@code i}.
 */
public final class Channel {

    /** A raw keyframe, units already converted. */
    public static final class Key {

        final float tick;
        final boolean catmullRom;
        final Vec3Expr pre;
        final Vec3Expr post;

        public Key(float tick, boolean catmullRom, Vec3Expr pre, Vec3Expr post) {
            this.tick = tick;
            this.catmullRom = catmullRom;
            this.pre = pre;
            this.post = post == null ? pre : post;
        }
    }

    static final int TRANSITION = 0;
    static final int LINEAR = 1;
    static final int CATMULL_ROM = 2;

    /** One segment between two keyframes. */
    static final class Segment {

        final int type;
        final float startTick;
        final float totalTick;
        final float endTick;
        /** left neighbour (Catmull-Rom only) */
        final Vec3Expr left;
        final Vec3Expr begin;
        final Vec3Expr end;
        /** right neighbour (Catmull-Rom only) */
        final Vec3Expr right;
        /** value reached at the very end of the segment */
        final Vec3Expr post;

        Segment(int type, float startTick, float totalTick, Vec3Expr left, Vec3Expr begin, Vec3Expr end, Vec3Expr right,
            Vec3Expr post) {
            this.type = type;
            this.startTick = startTick;
            this.totalTick = totalTick;
            this.endTick = startTick + totalTick;
            this.left = left;
            this.begin = begin;
            this.end = end;
            this.right = right;
            this.post = post;
        }

        /** Percent of the segment at {@code tick}, as upstream {@code AnimationPoint.getPercentCompleted}. */
        float percent(float tick) {
            return totalTick == 0 ? 1 : (tick - startTick) / totalTick;
        }

        /**
         * @param scratch at least 12 floats (Catmull-Rom)
         */
        void sample(MolangContext ctx, float percent, float[] out, int o, float[] scratch) {
            if (type == TRANSITION) {
                (isEnd(percent) ? post : begin).eval(ctx, out, o);
                return;
            }
            if (isBegin(percent)) {
                begin.eval(ctx, out, o);
                return;
            }
            if (isEnd(percent)) {
                post.eval(ctx, out, o);
                return;
            }
            if (type == LINEAR) {
                begin.eval(ctx, scratch, 0);
                end.eval(ctx, scratch, 3);
                for (int i = 0; i < 3; i++) {
                    out[o + i] = AnimMath.lerp(percent, scratch[i], scratch[3 + i]);
                }
                return;
            }
            left.eval(ctx, scratch, 0);
            begin.eval(ctx, scratch, 3);
            end.eval(ctx, scratch, 6);
            right.eval(ctx, scratch, 9);
            for (int i = 0; i < 3; i++) {
                out[o + i] = AnimMath.catmullRom(percent, scratch[i], scratch[3 + i], scratch[6 + i], scratch[9 + i]);
            }
        }
    }

    static boolean isBegin(float percent) {
        return percent < 0.00001;
    }

    static boolean isEnd(float percent) {
        return percent > 0.99999f;
    }

    final Segment[] segments;

    private Channel(Segment[] segments) {
        this.segments = segments;
    }

    /**
     * Builds the segments from keyframes sorted by tick, as upstream {@code BoneKeyFrameProcessor}: a segment eases
     * Catmull-Rom when its end keyframe says so, or when its begin keyframe does (Blockbench's rule).
     *
     * @return null for no keyframes
     */
    public static Channel of(List<Key> keys) {
        if (keys.isEmpty()) {
            return null;
        }
        Segment[] segments = new Segment[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            Key end = keys.get(i);
            if (i == 0) {
                segments[0] = new Segment(TRANSITION, 0, end.tick, null, end.pre, end.pre, null, end.post);
                continue;
            }
            Key begin = keys.get(i - 1);
            boolean catmull = end.catmullRom || begin.catmullRom;
            float total = end.tick - begin.tick;
            if (catmull) {
                Key left = keys.get(Math.max(0, i - 2));
                Key right = keys.get(Math.min(keys.size() - 1, i + 1));
                segments[i] = new Segment(
                    CATMULL_ROM,
                    begin.tick,
                    total,
                    left.post,
                    begin.post,
                    end.pre,
                    right.pre,
                    end.post);
            } else {
                segments[i] = new Segment(LINEAR, begin.tick, total, null, begin.post, end.pre, null, end.post);
            }
        }
        return new Channel(segments);
    }

    /** The segment playing at {@code tick}: the first whose end is after it, else the last. */
    Segment search(float tick) {
        Segment[] s = segments;
        if (s.length == 1 || tick < s[0].endTick) {
            return s[0];
        }
        int lo = 1, hi = s.length - 1;
        if (tick >= s[hi].endTick) {
            return s[hi];
        }
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (tick < s[mid].endTick) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return s[lo];
    }

    /** Where the beginning transition goes: the first keyframe's {@code pre} value. */
    Vec3Expr transitionTarget() {
        return segments[0].begin;
    }

    /** Samples the channel at {@code tick} (no transition). */
    public void sample(MolangContext ctx, float tick, float[] out, int o, float[] scratch) {
        Segment segment = search(tick);
        segment.sample(ctx, segment.percent(tick), out, o, scratch);
    }

    public int keyCount() {
        return segments.length;
    }
}

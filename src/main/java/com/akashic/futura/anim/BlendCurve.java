/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/transition/IBlendTransition.java,
 * LinearBlendTransition.java and SegmentedBlendTransition.java, and the blend part of
 * src/main/java/com/elfmcys/ysm/model/resource/client/render/AnimationProtoMapper.java, Apache License 2.0.
 * Changes: one class; segments are searched by binary search (stateless, so {@code startNew} is not needed).
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.pojo.controller.BlendTransition;

/**
 * The beginning transition of an animation: how long it takes and how far it is at each tick. Linear (a length in
 * seconds) or a curve of {@code "seconds": weight-of-the-old-pose} points ({@code blend_transition} of a Bedrock
 * controller state). Immutable.
 */
public final class BlendCurve {

    /** No transition. */
    public static final BlendCurve NONE = linear(0);

    /** Segment start ticks, end ticks and progress at both ends; empty for a linear curve. */
    private final float[] start;
    private final float[] end;
    private final float[] from;
    private final float[] to;
    private final float length;

    private BlendCurve(float length, float[] start, float[] end, float[] from, float[] to) {
        this.length = length;
        this.start = start;
        this.end = end;
        this.from = from;
        this.to = to;
    }

    /** Upstream {@code LinearBlendTransition}. */
    public static BlendCurve linear(float seconds) {
        return new BlendCurve(seconds * 20, null, null, null, null);
    }

    /**
     * Upstream {@code SegmentedBlendTransition}: consecutive points, each the weight the old pose keeps (1 at the
     * start,
     * 0 at the end); progress is {@code 1 - weight}, linear between points.
     *
     * @param seconds  point times, ascending
     * @param oldPoses the old pose's weight at each point
     */
    public static BlendCurve segmented(float[] seconds, float[] oldPoses) {
        int n = seconds.length - 1;
        if (n < 1) {
            return NONE;
        }
        float[] s = new float[n], e = new float[n], f = new float[n], t = new float[n];
        for (int i = 0; i < n; i++) {
            s[i] = seconds[i] * 20;
            e[i] = seconds[i + 1] * 20;
            f[i] = 1 - oldPoses[i];
            t[i] = 1 - oldPoses[i + 1];
        }
        return new BlendCurve(e[n - 1], s, e, f, t);
    }

    /**
     * A state's {@code blend_transition}, as upstream maps it: a number is linear, an object with at least two points
     * is a curve (sorted by time), anything else is no transition.
     */
    public static BlendCurve of(BlendTransition blend) {
        if (blend == null) {
            return NONE;
        }
        if (blend.linearLength != null) {
            return linear(blend.linearLength);
        }
        if (blend.points == null || blend.points.size() < 2) {
            return NONE;
        }
        List<Map.Entry<Float, Float>> points = new ArrayList<>(blend.points.entrySet());
        points.sort((a, b) -> Float.compare(a.getKey(), b.getKey()));
        float[] times = new float[points.size()];
        float[] weights = new float[points.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = points.get(i)
                .getKey();
            Float w = points.get(i)
                .getValue();
            weights[i] = w == null ? 0 : w;
        }
        return segmented(times, weights);
    }

    /** Length in ticks. */
    public float length() {
        return length;
    }

    /** Progress (0 at the start, 1 at the end for a well-formed curve) at a tick of the transition. */
    public float progress(float tick) {
        if (start == null) {
            return length != 0 ? tick / length : 1;
        }
        int i = segment(tick);
        if (tick <= start[i]) {
            return from[i];
        }
        if (tick >= end[i]) {
            return to[i];
        }
        float total = end[i] - start[i];
        return from[i] + (to[i] - from[i]) * ((tick - start[i]) / total);
    }

    /** The segment {@code [end[i-1], end[i])} holding the tick (the first before it, the last past the end). */
    private int segment(float tick) {
        int lo = 0, hi = end.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (end[mid] <= tick) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}

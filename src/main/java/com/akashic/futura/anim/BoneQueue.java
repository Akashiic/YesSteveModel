/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/BoneAnimationQueue.java and the point classes in
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/point/, Apache License 2.0.
 * Changes: the per-frame point objects become reusable per-channel fields (kind, segment, progress, last result).
 */
package com.akashic.futura.anim;

/** One bone's playback state inside one {@link AnimationPlayer}. Channels: 0 rotation, 1 position, 2 scale. */
final class BoneQueue {

    static final int NONE = 0;
    /** a keyframe segment ({@code KeyFramePoint}) */
    static final int KEY = 1;
    /** blending in ({@code BeginningTransitionPoint}) */
    static final int BEGIN = 2;
    /** blending out ({@code EndingTransitionPoint}) */
    static final int END = 3;

    final int bone;
    final Channel[] channels = new Channel[3];
    boolean active;
    boolean disableEndingTransition;
    private float blendWeight = 1;

    /** Bone pose when the clip started: rotation delta, position (pixels), scale. */
    final float[] transitionOffset = new float[9];
    /** Where the ending transition starts, per channel. */
    final float[] endOffset = new float[9];
    final boolean[] hasEndOffset = new boolean[3];

    /** This frame's point per channel. */
    final int[] kind = new int[3];
    final Channel.Segment[] segment = new Channel.Segment[3];
    /** KEY: percent of the segment; BEGIN: transition progress; END: ending percent. */
    final float[] progress = new float[3];
    /** Raw value the point produced when polled ({@code lastLerpResult}). */
    final float[] lastLerp = new float[9];
    final boolean[] hasLastLerp = new boolean[3];

    BoneQueue(int bone) {
        this.bone = bone;
    }

    void setActive(AnimationClip.BoneTrack track, boolean disableEndingTransition, float[] committed) {
        channels[0] = track.rotation;
        channels[1] = track.position;
        channels[2] = track.scale;
        System.arraycopy(committed, bone * 9, transitionOffset, 0, 9);
        active = true;
        this.disableEndingTransition = disableEndingTransition;
        resetPoints();
    }

    void setInactive() {
        channels[0] = channels[1] = channels[2] = null;
        hasEndOffset[0] = hasEndOffset[1] = hasEndOffset[2] = false;
        active = false;
        resetPoints();
    }

    float blendWeight() {
        return blendWeight;
    }

    /** Never below 0 (as Blockbench). */
    void setBlendWeight(float weight) {
        blendWeight = weight > 0 ? weight : 0;
    }

    void resetPoints() {
        for (int c = 0; c < 3; c++) {
            kind[c] = NONE;
            segment[c] = null;
            hasLastLerp[c] = false;
        }
    }

    void point(int c, int kind, Channel.Segment segment, float progress) {
        this.kind[c] = kind;
        this.segment[c] = segment;
        this.progress[c] = progress;
        this.hasLastLerp[c] = false;
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/builder/Animation.java and
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/BoneAnimation.java, Apache License 2.0.
 * Changes: compiled from the format POJOs directly (no protobuf container); sound keyframes are not kept yet.
 */
package com.akashic.futura.anim;

import com.akashic.futura.format.pojo.animation.LoopType;
import com.akashic.futura.molang.MolangExpression;

/** One animation compiled for playback. Immutable and shared by every player using the model. */
public final class AnimationClip {

    /** The keyframes of one bone. Channels are null when the bone has no keyframes for them. */
    public static final class BoneTrack {

        public final String bone;
        public final Channel rotation;
        public final Channel position;
        public final Channel scale;

        BoneTrack(String bone, Channel rotation, Channel position, Channel scale) {
            this.bone = bone;
            this.rotation = rotation;
            this.position = position;
            this.scale = scale;
        }
    }

    /** {@code timeline} entry: Molang statements run when playback passes {@link #tick}. */
    public static final class Instruction {

        public final float tick;
        public final MolangExpression[] programs;

        Instruction(float tick, MolangExpression[] programs) {
            this.tick = tick;
            this.programs = programs;
        }
    }

    public final String name;
    /** Length in ticks ({@link Float#MAX_VALUE} when the file gives none and no keyframe after 0). */
    public final float length;
    public final LoopType loop;
    /** {@code blend_weight}, or null for 1. */
    public final Vec3Expr.Scalar blendWeight;
    public final BoneTrack[] tracks;
    /** Sorted by tick. */
    public final Instruction[] instructions;
    private final boolean hasSounds;

    AnimationClip(String name, float length, LoopType loop, Vec3Expr.Scalar blendWeight, BoneTrack[] tracks,
        Instruction[] instructions, boolean hasSounds) {
        this.name = name;
        this.length = length;
        this.loop = loop;
        this.blendWeight = blendWeight;
        this.tracks = tracks;
        this.instructions = instructions;
        this.hasSounds = hasSounds;
    }

    /** As upstream {@code Animation.isEmpty}: no bones, no sounds, no instructions. */
    public boolean isEmpty() {
        return tracks.length == 0 && instructions.length == 0 && !hasSounds;
    }
}

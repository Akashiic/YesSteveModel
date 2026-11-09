/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/AnimationBuilder.java,
 * src/main/java/com/elfmcys/ysm/model/resource/client/render/BakedAnimationMapper.java and
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/bone/RawBoneKeyFrame.java, Apache License 2.0.
 * Changes: one step from the format POJOs to runtime clips, without the protobuf container in between.
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.akashic.futura.format.pojo.animation.Animation;
import com.akashic.futura.format.pojo.animation.BoneAnimation;
import com.akashic.futura.format.pojo.animation.keyframe.BoneKeyFrame;
import com.akashic.futura.format.pojo.animation.keyframe.BoneKeyFrameList;
import com.akashic.futura.format.pojo.animation.keyframe.EasingType;
import com.akashic.futura.format.pojo.animation.keyframe.InstructionKeyFrame;
import com.akashic.futura.format.pojo.animation.value.UnionValue;
import com.akashic.futura.format.pojo.animation.value.Vector3v;
import com.akashic.futura.molang.Molang;
import com.akashic.futura.molang.MolangExpression;

/**
 * Compiles animation POJOs into {@link AnimationClip}s. Units, as upstream: times are already ticks in the POJOs;
 * rotation degrees become radians with x and y negated (z kept); position stays in pixels (the pose converts it to
 * blocks with x negated); scale is kept; a single value (scalar or one-element array) applies to all three axes.
 */
public final class ClipCompiler {

    private final Function<String, MolangExpression> parser;

    /** Uses {@link Molang#parse}. */
    public ClipCompiler() {
        this(Molang::parse);
    }

    /** @param parser turns a Molang source into an expression (tests stub it) */
    public ClipCompiler(Function<String, MolangExpression> parser) {
        this.parser = parser;
    }

    public AnimationClip compile(Animation src) {
        List<AnimationClip.BoneTrack> tracks = new ArrayList<>();
        for (BoneAnimation bone : src.boneAnimations) {
            Channel rotation = channel(bone.rotationKeyFrames, true);
            Channel position = channel(bone.positionKeyFrames, false);
            Channel scale = channel(bone.scaleKeyFrames, false);
            tracks.add(
                new AnimationClip.BoneTrack(bone.boneName == null ? "" : bone.boneName, rotation, position, scale));
        }
        List<AnimationClip.Instruction> instructions = new ArrayList<>();
        for (InstructionKeyFrame frame : src.customInstructionKeyframes) {
            if (frame.eventData.isEmpty()) {
                continue;
            }
            MolangExpression[] programs = new MolangExpression[frame.eventData.size()];
            for (int i = 0; i < programs.length; i++) {
                programs[i] = parser.apply(frame.eventData.get(i));
            }
            instructions.add(new AnimationClip.Instruction(frame.startTick, programs));
        }
        instructions.sort((a, b) -> Float.compare(a.tick, b.tick));
        Vec3Expr.Scalar blendWeight = src.blendWeight == null ? null : scalar(src.blendWeight, 1);
        return new AnimationClip(
            src.animationName == null ? "" : src.animationName,
            src.animationLength,
            src.loop,
            blendWeight,
            tracks.toArray(new AnimationClip.BoneTrack[0]),
            instructions.toArray(new AnimationClip.Instruction[0]),
            !src.soundKeyFrames.isEmpty());
    }

    private Channel channel(BoneKeyFrameList list, boolean rotation) {
        if (list == null || list.keyFrames.isEmpty()) {
            return null;
        }
        List<Channel.Key> keys = new ArrayList<>(list.keyFrames.size());
        for (BoneKeyFrame frame : list.keyFrames) {
            Vec3Expr pre = vector(frame.preValue, rotation);
            Vec3Expr post = frame.postValue == null ? null : vector(frame.postValue, rotation);
            keys.add(new Channel.Key(frame.startTick, frame.easingType == EasingType.CATMULLROM, pre, post));
        }
        keys.sort((a, b) -> Float.compare(a.tick, b.tick));
        return Channel.of(keys);
    }

    private Vec3Expr vector(Vector3v value, boolean rotation) {
        float fx = rotation ? -AnimMath.DEG_TO_RAD : 1;
        float fz = rotation ? AnimMath.DEG_TO_RAD : 1;
        if (value == null || value.components == null || value.components.isEmpty()) {
            return Vec3Expr.constant(0, 0, 0);
        }
        List<UnionValue> c = value.components;
        if (c.size() >= 3) {
            return new Vec3Expr(scalar(c.get(0), fx), scalar(c.get(1), fx), scalar(c.get(2), fz));
        }
        Vec3Expr.Scalar one = scalar(c.get(0), 1);
        return new Vec3Expr(one.scaled(fx), one.scaled(fx), one.scaled(fz));
    }

    private Vec3Expr.Scalar scalar(UnionValue value, float factor) {
        if (value == null || value.type == UnionValue.ValueType.NONE) {
            return Vec3Expr.Scalar.constant(0);
        }
        if (value.type == UnionValue.ValueType.FLOAT) {
            return Vec3Expr.Scalar.constant(value.floatValue * factor);
        }
        return Vec3Expr.Scalar.of(parser.apply(value.stringValue), factor);
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/AnimationPlayer.java,
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/transition/LinearBlendTransition.java and
 * src/main/java/com/elfmcys/ysm/geckolib3/core/event/InstructionKeyFrameExecutor.java, Apache License 2.0.
 * Changes: points are per-channel fields of the bone queues instead of objects; animation resolution is a plain
 * library lookup (always stable, so no retry); sound keyframes are not played yet.
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.List;

import com.akashic.futura.format.pojo.animation.LoopType;
import com.akashic.futura.molang.MolangExpression;

/**
 * Plays one clip at a time for one controller: beginning transition from the current pose, running (loop, play once,
 * hold on last frame), ending transition back over {@link #ENDING_TRANSITION_TICKS} ticks. It only fills the bone
 * queues with points; the {@link AnimationProcessor} samples and blends them.
 */
public final class AnimationPlayer {

    /** Upstream {@code AnimationData.DEFAULT_ENDING_TRANSITION_LENGTH}. */
    public static final float ENDING_TRANSITION_TICKS = 3;

    private final AnimationLibrary library;
    private final BoneIndex bones;
    private final float[] committed;
    private final BoneQueue[] queues;
    private final List<BoneQueue> active = new ArrayList<>();
    private final boolean disableBeginningTransitionScale;

    /** Beginning transition length in ticks. */
    private float beginningTicks;
    private PlaybackState state = PlaybackState.IDLE;
    private float animTickOffset;
    private float endingTransitionSrcTick;
    /** Seconds; what {@code query.anim_time} sees while this player's clip is evaluated. */
    private float animTime;

    private String lastName;
    private LoopType lastLoopOverride;
    private AnimationClip nextClip;
    private LoopType nextLoop;
    private AnimationClip current;
    private LoopType currentLoop;
    private int nextInstruction;
    private boolean currentAnimFinished = true;

    /**
     * @param committed the processor's committed pose (9 floats per bone), read when a clip starts
     * @param bones     maps clip bone names to bone indices; tracks on unknown bones are skipped
     */
    AnimationPlayer(AnimationLibrary library, float[] committed, BoneIndex bones, float transitionSeconds,
        boolean disableBeginningTransitionScale) {
        this.library = library;
        this.committed = committed;
        this.queues = new BoneQueue[bones.count()];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new BoneQueue(i);
        }
        this.bones = bones;
        this.beginningTicks = transitionSeconds * 20;
        this.disableBeginningTransitionScale = disableBeginningTransitionScale;
    }

    /** Bone names to indices. */
    public interface BoneIndex {

        int count();

        /** @return the index, or -1 */
        int indexOf(String bone);
    }

    /**
     * Selects a clip. The same request (name and loop override) as last time changes nothing: the clip keeps playing.
     * A different one drops the current clip and starts the new one from a beginning transition; a missing one leaves
     * the player idle.
     *
     * @param loopOverride null to use the clip's own loop type
     */
    public void setAnimation(String name, LoopType loopOverride) {
        if (name == null) {
            reset();
            return;
        }
        if (name.equals(lastName) && lastLoopOverride == loopOverride) {
            return;
        }
        AnimationLibrary.Resolution resolution = library.resolve(name);
        lastName = name;
        lastLoopOverride = loopOverride;
        nextClip = null;
        resetToIdle();
        if (resolution == null) {
            return;
        }
        nextClip = resolution.clip;
        nextLoop = loopOverride != null ? loopOverride : resolution.clip.loop;
    }

    /** Lets the next {@link #setAnimation} with the same name restart the clip. */
    public void indicateReload() {
        lastName = null;
    }

    /** Back to the initial state. */
    public void reset() {
        lastName = null;
        lastLoopOverride = null;
        nextClip = null;
        resetToIdle();
    }

    /** Starts the ending transition from the current pose. */
    public void stop(float renderTicks) {
        setupEndingTransition(renderTicks);
    }

    public void setBeginningTransitionSeconds(float seconds) {
        beginningTicks = seconds * 20;
    }

    public PlaybackState state() {
        return state;
    }

    /** The clip playing, or null when idle. */
    public AnimationClip current() {
        return current;
    }

    /** true once a loop has completed, a hold clip reached its end, or while idle or blending out. */
    public boolean currentAnimFinished() {
        return currentAnimFinished;
    }

    float animTime() {
        return animTime;
    }

    List<BoneQueue> activeQueues() {
        return active;
    }

    float animTicks(float renderTicks) {
        return Math.max(renderTicks - animTickOffset, 0f);
    }

    /**
     * Advances the timeline and fills this frame's points.
     *
     * @param renderTicks   the entity's animation time in ticks
     * @param executeEvents whether timeline instructions may run
     */
    public void process(float renderTicks, AnimationContext ctx, boolean executeEvents) {
        float savedTime = ctx.animTime;
        ctx.animTime = animTime;
        try {
            float animTicks = animTicks(renderTicks);
            if (state == PlaybackState.ENDING_TRANSITION && animTicks >= ENDING_TRANSITION_TICKS) {
                resetToIdle();
            }
            // Play-once ends one frame late so the last instruction runs and the next state can blend in.
            if (state == PlaybackState.RUNNING && currentLoop == LoopType.PLAY_ONCE && animTicks >= current.length) {
                resetEventKeyframes(ctx, executeEvents);
                setupEndingTransition(renderTicks);
                animTicks = animTicks(renderTicks);
            }
            if (state == PlaybackState.IDLE) {
                if (!loadNextAnim()) {
                    return;
                }
                animTickOffset = renderTicks;
                animTicks = 0;
                state = beginningTicks > 0 ? PlaybackState.BEGINNING_TRANSITION : PlaybackState.RUNNING;
            }
            resetBoneQueues();
            if (state == PlaybackState.BEGINNING_TRANSITION) {
                if (animTicks < beginningTicks) {
                    setAnimTime(ctx, 0);
                    runBeginningTransition(ctx, animTicks);
                    return;
                }
                animTicks -= beginningTicks;
                animTickOffset = renderTicks - animTicks;
                state = PlaybackState.RUNNING;
            }
            if (state == PlaybackState.RUNNING) {
                if (animTicks > current.length) {
                    currentAnimFinished = true;
                    if (currentLoop == LoopType.LOOP) {
                        animTicks = current.length > 0 ? animTicks % current.length : 0;
                        resetEventKeyframes(ctx, executeEvents);
                        animTickOffset = renderTicks - animTicks;
                    } else if (currentLoop == LoopType.HOLD_ON_LAST_FRAME) {
                        animTicks = current.length;
                    }
                }
                setAnimTime(ctx, animTicks / 20f);
                if (executeEvents) {
                    executeInstructionsTo(ctx, animTicks);
                }
                runAnimation(ctx, animTicks);
                return;
            }
            if (state == PlaybackState.ENDING_TRANSITION) {
                if (animTicks > ENDING_TRANSITION_TICKS) {
                    animTicks = ENDING_TRANSITION_TICKS;
                }
                setAnimTime(ctx, endingTransitionSrcTick / 20f);
                runEndingTransition(ctx, animTicks);
            }
        } finally {
            ctx.animTime = savedTime;
        }
    }

    private void setAnimTime(AnimationContext ctx, float seconds) {
        animTime = seconds;
        ctx.animTime = seconds;
    }

    private void resetEventKeyframes(AnimationContext ctx, boolean executeEvents) {
        setAnimTime(ctx, current.length / 20f);
        if (executeEvents) {
            AnimationClip.Instruction[] list = current.instructions;
            for (int i = nextInstruction; i < list.length; i++) {
                run(ctx, list[i].programs);
            }
            nextInstruction = 0;
        }
    }

    private void executeInstructionsTo(AnimationContext ctx, float animTicks) {
        AnimationClip.Instruction[] list = current.instructions;
        while (nextInstruction < list.length && list[nextInstruction].tick <= animTicks) {
            run(ctx, list[nextInstruction].programs);
            nextInstruction++;
        }
    }

    private static void run(AnimationContext ctx, MolangExpression[] programs) {
        for (MolangExpression program : programs) {
            try {
                program.evaluate(ctx);
            } catch (RuntimeException ignored) {
                // Molang is fail-soft; a broken program does nothing.
            }
        }
    }

    /** Saves the last sampled values as the start of the ending transition. */
    private void setupEndingTransition(float renderTicks) {
        if (state != PlaybackState.RUNNING && state != PlaybackState.BEGINNING_TRANSITION) {
            return;
        }
        float animTick = animTicks(renderTicks);
        for (BoneQueue q : active) {
            for (int c = 0; c < 3; c++) {
                if (q.kind[c] != BoneQueue.NONE && q.hasLastLerp[c]) {
                    System.arraycopy(q.lastLerp, c * 3, q.endOffset, c * 3, 3);
                    q.hasEndOffset[c] = true;
                }
            }
        }
        animTickOffset = renderTicks;
        if (state == PlaybackState.RUNNING) {
            endingTransitionSrcTick = Math.min(animTick, current.length);
        } else {
            endingTransitionSrcTick = 0;
        }
        currentAnimFinished = true;
        state = PlaybackState.ENDING_TRANSITION;
    }

    private float blendWeight(AnimationContext ctx) {
        return current.blendWeight != null ? current.blendWeight.eval(ctx) : 1;
    }

    private void runBeginningTransition(AnimationContext ctx, float ticks) {
        float weight = blendWeight(ctx);
        float progress = beginningTicks != 0 ? ticks / beginningTicks : 1;
        for (BoneQueue q : active) {
            q.setBlendWeight(weight);
            if (q.channels[0] != null) {
                q.point(0, BoneQueue.BEGIN, null, progress);
            }
            if (q.channels[1] != null) {
                q.point(1, BoneQueue.BEGIN, null, progress);
            }
            if (q.channels[2] != null) {
                q.point(2, BoneQueue.BEGIN, null, disableBeginningTransitionScale ? 1f : progress);
            }
        }
    }

    private void runAnimation(AnimationContext ctx, float ticks) {
        float weight = blendWeight(ctx);
        for (BoneQueue q : active) {
            q.setBlendWeight(weight);
            for (int c = 0; c < 3; c++) {
                Channel channel = q.channels[c];
                if (channel != null) {
                    Channel.Segment segment = channel.search(ticks);
                    q.point(c, BoneQueue.KEY, segment, segment.percent(ticks));
                }
            }
        }
    }

    private void runEndingTransition(AnimationContext ctx, float ticks) {
        float weight = blendWeight(ctx);
        for (BoneQueue q : active) {
            q.setBlendWeight(weight);
            float total = q.disableEndingTransition ? 0 : ENDING_TRANSITION_TICKS;
            float percent = total == 0 ? (ticks == 0 ? 0 : 1) : ticks / total;
            for (int c = 0; c < 3; c++) {
                if (q.hasEndOffset[c]) {
                    q.point(c, BoneQueue.END, null, percent);
                }
            }
        }
    }

    /** Clears this frame's points (nothing is sampled until the next process). */
    public void resetBoneQueues() {
        if (state != PlaybackState.IDLE) {
            for (BoneQueue q : active) {
                q.resetPoints();
            }
        }
    }

    private boolean loadNextAnim() {
        AnimationClip next = nextClip;
        if (next == null) {
            return false;
        }
        nextClip = null;
        current = next;
        currentLoop = nextLoop;
        currentAnimFinished = false;
        for (AnimationClip.BoneTrack track : current.tracks) {
            int bone = bones.indexOf(track.bone);
            if (bone < 0) {
                // e.g. the "molang" pseudo-bone: channels of bones the geometry lacks are skipped
                continue;
            }
            BoneQueue q = queues[bone];
            if (q.active) {
                continue;
            }
            q.setActive(track, track.scale != null, committed);
            active.add(q);
        }
        nextInstruction = 0;
        return true;
    }

    private void resetToIdle() {
        if (state == PlaybackState.IDLE) {
            return;
        }
        state = PlaybackState.IDLE;
        for (BoneQueue q : active) {
            q.setInactive();
        }
        active.clear();
        current = null;
        currentAnimFinished = true;
    }
}

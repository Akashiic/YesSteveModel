/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/CodedAnimationController.java, Apache License 2.0.
 * Changes: the Molang override handler ({@code player_ctrl_<name>} functions) is left to Tier 2.
 */
package com.akashic.futura.anim;

import com.akashic.futura.format.pojo.animation.LoopType;

/** A controller driven by a built-in predicate that picks one animation per frame. */
public final class CodedController implements AnimationController {

    /** Picks the animation of a coded controller each frame. */
    @FunctionalInterface
    public interface Predicate {

        /** Calls {@link CodedController#setAnimation} as needed and says how to play. */
        PlayState test(CodedController controller);
    }

    private final String name;
    private final Predicate predicate;
    private final AnimationPlayer player;
    private final boolean blendRotation;
    private boolean pause;

    /**
     * @param transitionSeconds beginning transition (upstream: 0.1 s for main/hold/use/vehicle/passenger, else 0)
     * @param blendRotation     true for the legacy additive {@code parallel*} controllers
     */
    CodedController(String name, AnimationLibrary library, float[] committed, AnimationPlayer.BoneIndex bones,
        float transitionSeconds, boolean blendRotation, Predicate predicate) {
        this.name = name;
        this.predicate = predicate;
        this.blendRotation = blendRotation;
        // Coded controllers never blend scale in (upstream passes disableBeginningTransitionScale = true).
        this.player = new AnimationPlayer(library, committed, bones, transitionSeconds, true);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void process(float renderTicks, AnimationContext ctx, boolean executeEvents) {
        AnimationController saved = ctx.controller;
        ctx.controller = this;
        try {
            PlayState playState = predicate.test(this);
            if (playState == PlayState.CONTINUE) {
                player.process(renderTicks, ctx, executeEvents);
                pause = false;
            } else if (playState == PlayState.STOP) {
                PlaybackState state = player.state();
                if (state == PlaybackState.BEGINNING_TRANSITION || state == PlaybackState.RUNNING) {
                    player.stop(renderTicks);
                    player.indicateReload();
                }
                if (state == PlaybackState.ENDING_TRANSITION) {
                    player.process(renderTicks, ctx, executeEvents);
                }
                pause = false;
            } else {
                player.process(renderTicks, ctx, false);
                player.resetBoneQueues();
                pause = true;
            }
        } finally {
            ctx.controller = saved;
        }
    }

    @Override
    public void visit(AnimationProcessor processor, AnimationContext ctx) {
        if (!pause) {
            processor.poll(player, ctx);
        }
    }

    @Override
    public boolean blendRotation() {
        return blendRotation && player.state() == PlaybackState.RUNNING;
    }

    @Override
    public PlaybackState state() {
        return player.state();
    }

    /** Selects an animation with its own loop type. */
    public void setAnimation(String name) {
        player.setAnimation(name, null);
    }

    /** @param loop forces the loop type, or null for the clip's own */
    public void setAnimation(String name, LoopType loop) {
        player.setAnimation(name, loop);
    }

    public void indicateReload() {
        player.indicateReload();
    }

    public void reset() {
        player.reset();
    }

    public boolean isAnimFinished() {
        return player.currentAnimFinished();
    }

    public void setBeginningTransitionSeconds(float seconds) {
        player.setBeginningTransitionSeconds(seconds);
    }

    public AnimationPlayer player() {
        return player;
    }
}

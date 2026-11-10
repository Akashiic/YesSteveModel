/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/CodedAnimationController.java, Apache License 2.0.
 * Changes: the override handler is a compiled {@code <category>_ctrl_<name>} function evaluated against the player
 * context; the result is read as a number (2 continue, 3 stop, 4 pause, anything else delegates).
 */
package com.akashic.futura.anim;

import com.akashic.futura.format.pojo.animation.LoopType;
import com.akashic.futura.molang.MolangExpression;
import com.akashic.futura.molang.MolangValue;

/**
 * A controller driven by a built-in predicate that picks one animation per frame. A model can override the predicate
 * with a function for the event {@code <category>_ctrl_<name>} ({@code player_ctrl_main} for {@code player.main}):
 * returning {@code ctrl.state_continue} (2), {@code ctrl.state_stop} (3) or 4 (pause) decides, after the function may
 * have called {@code ctrl.set_animation}; any other result hands over to the built-in predicate.
 */
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
    private final float defaultTransitionSeconds;
    private MolangExpression override;
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
        this.defaultTransitionSeconds = transitionSeconds;
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
        AnimationPlayer savedPlayer = ctx.player;
        ctx.controller = this;
        try {
            // upstream installs the player's animation context for the predicate: its sounds go to the player's scope
            ctx.player = player;
            PlayState playState;
            try {
                playState = override != null ? evalOverride(ctx) : null;
                if (playState == null) {
                    playState = predicate.test(this);
                }
            } finally {
                ctx.player = savedPlayer;
            }
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

    /** Upstream {@code Evaluation.codedController}: exactly 2, 3 or 4 decide; anything else is null (delegate). */
    private PlayState evalOverride(AnimationContext ctx) {
        MolangValue value;
        try {
            value = override.evaluate(ctx);
        } catch (RuntimeException e) {
            return null;
        }
        if (value == null || value.kind != MolangValue.Kind.NUMBER) {
            return null;
        }
        float v = (float) value.asNumber();
        if (v == 2f) {
            return PlayState.CONTINUE;
        }
        if (v == 3f) {
            return PlayState.STOP;
        }
        if (v == 4f) {
            return PlayState.PAUSE;
        }
        return null;
    }

    /** The model's {@code <category>_ctrl_<name>} handler, or null for the built-in predicate alone. */
    void setOverride(MolangExpression override) {
        this.override = override;
    }

    MolangExpression override() {
        return override;
    }

    @Override
    public boolean allAnimationsFinished() {
        return player.currentAnimFinished();
    }

    @Override
    public boolean anyAnimationFinished() {
        return player.currentAnimFinished();
    }

    @Override
    public String stateName() {
        AnimationClip clip = player.current();
        return clip == null ? "Coded" : "Coded -> " + clip.name;
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

    /** A Bedrock {@code ysm-builtin} state's blend for the clip that takes over. */
    public void setBeginningTransition(BlendCurve curve) {
        player.setBeginningTransition(curve);
    }

    /** The beginning transition the controller was built with. */
    float defaultTransitionSeconds() {
        return defaultTransitionSeconds;
    }

    public void stop(float renderTicks) {
        player.stop(renderTicks);
    }

    public AnimationPlayer player() {
        return player;
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/HybridAnimationController.java, Apache License 2.0.
 * Changes: built once per model with or without the Bedrock definition (no rebinding).
 */
package com.akashic.futura.anim;

/**
 * A coded controller that the model can replace: when its {@code animation_controllers} files declare a controller of
 * the same name ({@code player.main}), that Bedrock controller runs instead, and its {@code ysm-builtin} state hands
 * the frame back to the coded predicate (whose clip then blends in with the state's {@code blend_transition}). Without
 * a declaration it is the coded controller alone.
 */
public final class HybridController implements AnimationController {

    private final CodedController coded;
    private final BedrockController bedrock;
    private AnimationController active;

    /** @param bedrock the model's controller of the same name, or null */
    HybridController(CodedController coded, BedrockController bedrock) {
        this.coded = coded;
        this.bedrock = bedrock;
        this.active = bedrock != null ? bedrock : coded;
    }

    @Override
    public String name() {
        return coded.name();
    }

    /** The model's Bedrock controller, or null. */
    public BedrockController bedrock() {
        return bedrock;
    }

    public CodedController coded() {
        return coded;
    }

    /** The controller playing now: the Bedrock one, or the coded one (no declaration, or {@code ysm-builtin}). */
    public AnimationController active() {
        return active;
    }

    @Override
    public void process(float renderTicks, AnimationContext ctx, boolean executeEvents) {
        if (bedrock == null) {
            coded.process(renderTicks, ctx, executeEvents);
            return;
        }
        bedrock.process(renderTicks, ctx, executeEvents);
        if (bedrock.isBuiltinState()) {
            if (active != coded) {
                coded.setBeginningTransition(bedrock.currentState().blend);
                active = coded;
            }
            coded.process(renderTicks, ctx, executeEvents);
        } else if (active != bedrock) {
            coded.reset();
            active = bedrock;
        }
    }

    @Override
    public void visit(AnimationProcessor processor, AnimationContext ctx) {
        AnimationController saved = ctx.controller;
        ctx.controller = active;
        try {
            active.visit(processor, ctx);
        } finally {
            ctx.controller = saved;
        }
    }

    @Override
    public boolean blendRotation() {
        return active.blendRotation();
    }

    @Override
    public PlaybackState state() {
        return active.state();
    }

    @Override
    public boolean allAnimationsFinished() {
        return active.allAnimationsFinished();
    }

    @Override
    public boolean anyAnimationFinished() {
        return active.anyAnimationFinished();
    }

    @Override
    public SoundScope soundScope() {
        return active.soundScope();
    }

    @Override
    public String stateName() {
        if (bedrock == null) {
            return coded.stateName();
        }
        return bedrock.isBuiltinState() ? "[builtin] " + coded.stateName() : bedrock.stateName();
    }
}

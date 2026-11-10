/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/IAnimationController.java, Apache License 2.0.
 * Changes: reduced to what the processor needs; the controller execution state (finished flags, sound scope) is read
 * through it.
 */
package com.akashic.futura.anim;

/**
 * One layer of the controller stack. Controllers run in order each frame; for each bone and channel the later one
 * overwrites, except controllers whose {@link #blendRotation()} is true, which add their rotation.
 * <p>
 * Implemented by {@link CodedController} (built-in predicates), {@link BedrockController} (a state machine from the
 * model's {@code animation_controllers} files) and {@link HybridController} (a coded controller that a Bedrock
 * controller of the same name replaces when the model declares one).
 */
public interface AnimationController {

    /** e.g. {@code player.main} */
    String name();

    /** Runs the predicate or state machine and advances the timelines. */
    void process(float renderTicks, AnimationContext ctx, boolean executeEvents);

    /** Hands this frame's playing animations to the processor ({@link AnimationProcessor#poll}). */
    void visit(AnimationProcessor processor, AnimationContext ctx);

    /** Legacy YSM blending: true for the {@code parallel*} controllers while running (rotation is added). */
    default boolean blendRotation() {
        return false;
    }

    /** Playback state, as {@code ctrl.*} and the extra-animation bookkeeping see it. */
    PlaybackState state();

    /** {@code query.all_animations_finished} while this controller runs Molang. */
    default boolean allAnimationsFinished() {
        return false;
    }

    /** {@code query.any_animation_finished} while this controller runs Molang. */
    default boolean anyAnimationFinished() {
        return false;
    }

    /** The controller's own sound scope (Bedrock controllers have one), or null. */
    default SoundScope soundScope() {
        return null;
    }

    /** The state machine's current state, for debugging and the harness ({@code Coded} for coded controllers). */
    default String stateName() {
        return "Coded";
    }
}

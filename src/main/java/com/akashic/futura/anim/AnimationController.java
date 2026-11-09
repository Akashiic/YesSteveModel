/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/IAnimationController.java, Apache License 2.0.
 * Changes: reduced to what the processor needs.
 */
package com.akashic.futura.anim;

/**
 * One layer of the controller stack. Controllers run in order each frame; for each bone and channel the later one
 * overwrites, except controllers whose {@link #blendRotation()} is true, which add their rotation.
 * <p>
 * Implemented by {@link CodedController} (built-in predicates). Bedrock animation controllers from model files
 * ({@code animation_controllers}, upstream {@code BedrockAnimationController} / {@code HybridAnimationController})
 * are the Tier 2 seam: they implement this interface and replace a coded controller of the same name in
 * {@link PlayerControllers}.
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
}

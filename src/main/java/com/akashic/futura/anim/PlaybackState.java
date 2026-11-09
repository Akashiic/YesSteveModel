/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/AnimationState.java, Apache License 2.0.
 * Changes: renamed (the main-state table has its own AnimationState upstream).
 */
package com.akashic.futura.anim;

/** State of an {@link AnimationPlayer}. */
public enum PlaybackState {
    IDLE,
    /** Blending from the current pose into the clip's first keyframe. */
    BEGINNING_TRANSITION,
    RUNNING,
    /** After a play-once clip ends or the controller stops: blending out over 3 ticks. */
    ENDING_TRANSITION
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/PlayState.java, Apache License 2.0.
 * Changes: package renamed, documented.
 */
package com.akashic.futura.anim;

/** What a coded controller's predicate asks for this frame. */
public enum PlayState {
    /** Advance and sample the selected animation. */
    CONTINUE,
    /** End the animation (through its ending transition). */
    STOP,
    /** Keep the timeline running but contribute nothing this frame. */
    PAUSE
}

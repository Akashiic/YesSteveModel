/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/animation/physics/IPhysics.java, Apache License 2.0.
 * Changes: package renamed.
 */
package com.akashic.futura.anim.physics;

/** A per-entity filter driven by a Molang call ({@code ysm.second_order}, {@code ysm.first_order}). */
public interface Physics {

    /** Advances the simulation by {@code timeStep} seconds. */
    void update(float timeStep);

    /** The latest call's arguments (input first). */
    void setArgs(float arg0, float arg1, float arg2, float arg3);

    float getValue();
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/animation/physics/FirstOrder.java, Apache License 2.0.
 * Changes: package renamed; a response of 0 follows the input instead of dividing by zero.
 */
package com.akashic.futura.anim.physics;

/** {@code ysm.first_order(name, x, R)}: {@code y = (1 − dt/R)·y + (dt/R)·x}. */
public final class FirstOrder implements Physics {

    private float input;
    private float response;
    private float lastSimulation = 0;

    public FirstOrder(float input, float response) {
        this.input = input;
        this.response = response;
    }

    @Override
    public void update(float timeStep) {
        if (response == 0) {
            lastSimulation = input;
            return;
        }
        lastSimulation = (1 - timeStep / response) * lastSimulation + timeStep / response * input;
    }

    @Override
    public void setArgs(float arg0, float arg1, float arg2, float arg3) {
        this.input = arg0;
        this.response = arg1;
    }

    @Override
    public float getValue() {
        return lastSimulation;
    }
}

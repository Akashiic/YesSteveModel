/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/animation/physics/SecondOrder.java (author MicroCraft), Apache License 2.0.
 * Changes: package renamed; a frequency of 0 no longer divides by zero (the state holds, which is what upstream ends
 * up doing: its step count becomes NaN, so the loop never runs).
 */
package com.akashic.futura.anim.physics;

/**
 * Second-order dynamics (t3ssel8r, "Giving Personality to Procedural Animations using Math"):
 * {@code ysm.second_order(name, x, f = 1, ζ = 1, r = 1)}, f clamped to [0, 5] Hz, ζ to [0, 1].
 *
 * <pre>
 * k1 = ζ/(πf);  k2 = 1/(2πf)²;  k3 = r·ζ/(2πf)
 * ẋ = (x − x_prev)/dt
 * steps = ceil(dt / (sqrt(4·k2 + k1²) − k1)),  h = dt/steps
 * per step:  y += h·ẏ;  ẏ += h·(k3·ẋ + x − y − k1·ẏ)/k2
 * </pre>
 */
public final class SecondOrder implements Physics {

    private static final float PI = (float) Math.PI;

    private float inputFunction = 0;
    private float lastSimulation = 0;
    private float lastSimulationDot = 0;
    private float arg0;
    private float arg1;
    private float arg2;
    private float arg3;

    public SecondOrder(float input, float frequency, float coefficient, float response) {
        this.arg0 = input;
        this.arg1 = clamp(frequency, 0, 5);
        this.arg2 = clamp(coefficient, 0, 1);
        this.arg3 = response;
    }

    @Override
    public void update(float timeStep) {
        float input = arg0;
        float frequency = clamp(arg1, 0, 5);
        float coefficient = clamp(arg2, 0, 1);
        float response = arg3;

        float inputFunctionDot = (input - this.inputFunction) / timeStep;
        this.inputFunction = input;
        if (!(frequency > 0) || !(timeStep > 0)) {
            return;
        }

        float k1 = coefficient / PI / frequency;
        float k2 = 1 / (2 * PI * frequency) / (2 * PI * frequency);
        float k3 = response * coefficient / 2 / PI / frequency;

        float maxTimeStep = (float) Math.sqrt(4 * k2 + k1 * k1) - k1;
        int cycleTime = (int) Math.ceil(timeStep / maxTimeStep);
        if (cycleTime <= 0) {
            return;
        }
        timeStep = timeStep / cycleTime;

        float dot = this.lastSimulationDot;
        float sim = this.lastSimulation;
        for (; cycleTime > 0; cycleTime--) {
            sim = sim + timeStep * dot;
            dot = dot + timeStep * (k3 * inputFunctionDot + input - sim - k1 * dot) / k2;
        }
        this.lastSimulation = sim;
        this.lastSimulationDot = dot;
    }

    @Override
    public void setArgs(float arg0, float arg1, float arg2, float arg3) {
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.arg2 = arg2;
        this.arg3 = arg3;
    }

    @Override
    public float getValue() {
        return lastSimulation;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : v > max ? max : v;
    }
}

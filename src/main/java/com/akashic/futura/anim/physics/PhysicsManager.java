/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/animation/physics/PhysicsManager.java and the physics() helper of
 * src/main/java/com/elfmcys/ysm/expression/host/builtin/ModelBuiltins.java, Apache License 2.0.
 * Changes: keyed by name (upstream interns the name); the Molang entry points live here.
 */
package com.akashic.futura.anim.physics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The physics filters of one entity, by name. Updated once per frame before the animations are evaluated. */
public final class PhysicsManager {

    private final Map<String, Physics> values = new HashMap<>();
    private final List<Physics> list = new ArrayList<>();
    private float lastRenderTicks = 0;

    /** Advances every filter by the render time since the last call ({@code dt = Δticks / 20}). */
    public void update(float renderTicks) {
        if (lastRenderTicks > 0) {
            if (renderTicks > lastRenderTicks) {
                float interval = (renderTicks - lastRenderTicks) / 20f;
                lastRenderTicks = renderTicks;
                for (int i = 0; i < list.size(); i++) {
                    list.get(i)
                        .update(interval);
                }
            }
        } else {
            lastRenderTicks = renderTicks;
        }
    }

    /**
     * {@code ysm.first_order} / {@code ysm.second_order}: the first call with a name creates the filter and returns
     * the input as is; later calls update its arguments and return its value (one frame late).
     *
     * @param coefficients f, ζ, r for second order; R for first order (missing ones are 1)
     */
    public float call(String name, float input, boolean firstOrder, float a, float b, float c) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        Physics current = values.get(name);
        if (current == null) {
            current = firstOrder ? new FirstOrder(input, a) : new SecondOrder(input, a, b, c);
            values.put(name, current);
            list.add(current);
            return input;
        }
        current.setArgs(input, a, b, c);
        return current.getValue();
    }

    public Physics get(String name) {
        return values.get(name);
    }

    public void reset() {
        lastRenderTicks = 0;
        values.clear();
        list.clear();
    }
}

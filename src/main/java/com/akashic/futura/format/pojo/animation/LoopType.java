/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/builder/LoopType.java, Apache License 2.0.
 * Changes: package renamed.
 */
package com.akashic.futura.format.pojo.animation;

import com.google.gson.JsonElement;

public enum LoopType {

    LOOP,
    PLAY_ONCE,
    HOLD_ON_LAST_FRAME;

    public static LoopType fromJson(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            return PLAY_ONCE;
        }
        if (json.isJsonPrimitive()) {
            var primitive = json.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean() ? LOOP : PLAY_ONCE;
            }
            if (primitive.isString()) {
                return fromString(primitive.getAsString());
            }
        }
        return PLAY_ONCE;
    }

    private static LoopType fromString(String value) {
        if ("false".equalsIgnoreCase(value) || "PLAY_ONCE".equalsIgnoreCase(value)) {
            return PLAY_ONCE;
        }
        if ("true".equalsIgnoreCase(value) || "LOOP".equalsIgnoreCase(value)) {
            return LOOP;
        }
        if ("HOLD_ON_LAST_FRAME".equalsIgnoreCase(value)) {
            return HOLD_ON_LAST_FRAME;
        }
        return PLAY_ONCE;
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/bone/EasingType.java, Apache License 2.0.
 * Changes: reduced to the parsed easing kind; the keyframe builders move to the animation runtime.
 */
package com.akashic.futura.format.pojo.animation.keyframe;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** How a keyframe is reached from the previous one. Upstream honours only these two; anything else is linear. */
public enum EasingType {

    LINEAR,
    CATMULLROM;

    public static EasingType fromJson(JsonElement json) {
        if (json == null || json.isJsonNull() || !json.isJsonPrimitive()) {
            return LINEAR;
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (!primitive.isString()) {
            return LINEAR;
        }
        return "catmullrom".equalsIgnoreCase(primitive.getAsString()) ? CATMULLROM : LINEAR;
    }
}

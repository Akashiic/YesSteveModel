/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/controller/BlendTransition.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.controller;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

public class BlendTransition {

    public Float linearLength;
    public LinkedHashMap<Float, Float> points = new LinkedHashMap<>();

    public void setLinear(float length) {
        linearLength = length;
        points.clear();
    }

    public static final class Adapter implements JsonDeserializer<BlendTransition> {

        @Override
        public BlendTransition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            var transition = new BlendTransition();
            if (json == null || json.isJsonNull()) {
                return transition;
            }
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive()
                .isNumber()) {
                transition.setLinear(json.getAsFloat());
            } else if (json.isJsonObject()) {
                transition.linearLength = null;
                transition.points.clear();
                for (Map.Entry<String, JsonElement> item : json.getAsJsonObject()
                    .entrySet()) {
                    transition.points.put(
                        Float.parseFloat(item.getKey()),
                        item.getValue()
                            .getAsFloat());
                }
            }
            return transition;
        }
    }
}

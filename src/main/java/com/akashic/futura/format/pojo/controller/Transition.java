/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/controller/Transition.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.controller;

import java.lang.reflect.Type;
import java.util.Map;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

public class Transition {

    public String destStateName = "";
    public String condition = "";

    public static final class Adapter implements JsonDeserializer<Transition> {

        @Override
        public Transition deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            var transition = new Transition();
            if (json == null || json.isJsonNull() || !json.isJsonObject()) {
                return transition;
            }
            for (Map.Entry<String, JsonElement> item : json.getAsJsonObject()
                .entrySet()) {
                transition.destStateName = item.getKey();
                transition.condition = item.getValue()
                    .getAsString();
                break;
            }
            return transition;
        }
    }
}

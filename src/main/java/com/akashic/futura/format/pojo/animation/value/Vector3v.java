/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/animation/value/Vector3v.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.animation.value;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

public class Vector3v {

    public List<UnionValue> components;

    public Vector3v(UnionValue scalar) {
        components = Collections.singletonList(scalar);
    }

    public Vector3v(UnionValue x, UnionValue y, UnionValue z) {
        components = Collections.unmodifiableList(Arrays.asList(x, y, z));
    }

    public static Vector3v fromJson(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            throw new JsonParseException("Invalid vector: null");
        }
        if (json.isJsonArray()) {
            var array = json.getAsJsonArray();
            if (array.size() == 3) {
                return new Vector3v(
                    UnionValue.fromJson(array.get(0)),
                    UnionValue.fromJson(array.get(1)),
                    UnionValue.fromJson(array.get(2)));
            }
            if (array.size() == 1) {
                var value = array.get(0);
                return new Vector3v(UnionValue.fromJson(value));
            }
        } else if (json.isJsonPrimitive()) {
            return new Vector3v(UnionValue.fromJson(json));
        }
        throw new JsonParseException("Invalid vector: " + json);
    }

    public static final class Adapter implements JsonDeserializer<Vector3v> {

        @Override
        public Vector3v deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            return fromJson(json);
        }
    }
}

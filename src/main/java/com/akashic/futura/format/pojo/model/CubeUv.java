/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/model/CubeUv.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.model;

import java.lang.reflect.Type;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class CubeUv {

    public float[] boxUv;
    public UvFaces perFaceUv;

    public static CubeUv box(float[] boxUv) {
        CubeUv value = new CubeUv();
        value.boxUv = boxUv;
        return value;
    }

    public static CubeUv perFace(UvFaces perFaceUv) {
        CubeUv value = new CubeUv();
        value.perFaceUv = perFaceUv;
        return value;
    }

    public static final class Adapter implements JsonDeserializer<CubeUv>, JsonSerializer<CubeUv> {

        @Override
        public CubeUv deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            if (json.isJsonArray()) {
                return CubeUv.box(context.deserialize(json, float[].class));
            }
            if (json.isJsonObject()) {
                return CubeUv.perFace(context.deserialize(json, UvFaces.class));
            }
            throw new JsonParseException("Expected cube uv to be an array or object");
        }

        @Override
        public JsonElement serialize(CubeUv src, Type typeOfSrc, JsonSerializationContext context) {
            if (src == null) {
                return JsonNull.INSTANCE;
            }
            if (src.boxUv != null) {
                return context.serialize(src.boxUv);
            }
            if (src.perFaceUv != null) {
                return context.serialize(src.perFaceUv);
            }
            return JsonNull.INSTANCE;
        }
    }
}

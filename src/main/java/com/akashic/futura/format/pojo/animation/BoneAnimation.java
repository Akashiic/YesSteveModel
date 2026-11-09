/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/animation/BoneAnimation.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.animation;

import java.lang.reflect.Type;

import com.akashic.futura.format.pojo.animation.keyframe.BoneKeyFrameList;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

public class BoneAnimation {

    public String boneName = "";

    @SerializedName("rotation")
    public BoneKeyFrameList rotationKeyFrames;

    @SerializedName("position")
    public BoneKeyFrameList positionKeyFrames;

    @SerializedName("scale")
    public BoneKeyFrameList scaleKeyFrames;

    public BoneAnimation() {
        rotationKeyFrames = new BoneKeyFrameList();
        positionKeyFrames = new BoneKeyFrameList();
        scaleKeyFrames = new BoneKeyFrameList();
    }

    private BoneAnimation(JsonObject object) {
        var name = object.get("boneName");
        if (name != null) {
            if (!name.isJsonNull() && !name.isJsonPrimitive()) {
                throw new JsonParseException("Invalid bone name: " + name);
            }
            boneName = name.isJsonNull() ? null : name.getAsString();
        }
        rotationKeyFrames = readFrames(object, "rotation");
        positionKeyFrames = readFrames(object, "position");
        scaleKeyFrames = readFrames(object, "scale");
    }

    public static BoneAnimation fromJson(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            throw new JsonParseException("Invalid bone animation: " + json);
        }
        return new BoneAnimation(json.getAsJsonObject());
    }

    private static BoneKeyFrameList readFrames(JsonObject object, String name) {
        var json = object.get(name);
        // An omitted channel has its default list; explicit null must retain its presence semantics.
        if (json == null) return new BoneKeyFrameList();
        return json.isJsonNull() ? null : BoneKeyFrameList.fromJson(json);
    }

    public static final class Adapter implements JsonDeserializer<BoneAnimation> {

        @Override
        public BoneAnimation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
            return fromJson(json);
        }
    }
}

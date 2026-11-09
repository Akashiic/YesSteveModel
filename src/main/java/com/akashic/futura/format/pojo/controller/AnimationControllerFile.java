/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/controller/AnimationControllerFile.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.controller;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

public class AnimationControllerFile {

    @SerializedName("animation_controllers")
    public Map<String, AnimationController> animationControllers = new HashMap<>();
}

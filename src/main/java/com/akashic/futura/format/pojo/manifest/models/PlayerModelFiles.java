/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/models/PlayerModelFiles.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.models;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.google.gson.annotations.SerializedName;

public class PlayerModelFiles {

    public LinkedHashMap<String, String> model = new LinkedHashMap<>();
    public LinkedHashMap<String, String> animation = new LinkedHashMap<>();

    @SerializedName("animation_controllers")
    public List<String> animationControllers = new ArrayList<>();

    public List<PBRTextureSet> texture = new ArrayList<>();

    @Deprecated
    @SerializedName("sound_path")
    public String soundPath;
}

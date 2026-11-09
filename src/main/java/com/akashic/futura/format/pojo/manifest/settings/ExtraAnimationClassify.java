/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/settings/ExtraAnimationClassify.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.settings;

import java.util.LinkedHashMap;

import com.google.gson.annotations.SerializedName;

public class ExtraAnimationClassify {

    public String id = "";

    @SerializedName("extra_animation")
    public LinkedHashMap<String, String> extraAnimation = new LinkedHashMap<>();
}

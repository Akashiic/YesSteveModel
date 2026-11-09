/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/model/ExtraInfo.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.model;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

public class ExtraInfo {

    public String name = "";
    public String tips = "";

    @SerializedName("extra_animation_names")
    public List<String> extraAnimationNames = new ArrayList<>();

    public List<String> authors = new ArrayList<>();
    public String license = "All Rights Reserved";
    public boolean free;
}

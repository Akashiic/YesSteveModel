/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/settings/ExtraAnimationButton.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.settings;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

public class ExtraAnimationButton {

    public String id = "";
    public String name = "";
    public String sound = "";

    @SerializedName("config_forms")
    public List<ConfigForms> configForms = new ArrayList<>();
}

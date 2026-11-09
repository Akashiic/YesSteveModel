/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/settings/ConfigForms.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.settings;

import java.util.LinkedHashMap;

public class ConfigForms {

    public String type = "";
    public String title = "";
    public String description = "";
    public String value = "";
    public double step = 1;
    public double min = 0;
    public double max = 100;
    public LinkedHashMap<String, String> labels = new LinkedHashMap<>();
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/metadata/ModelAuthor.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.metadata;

import java.util.LinkedHashMap;

public class ModelAuthor {

    public String name = "";
    public String role = "";
    public String avatar = "";
    public LinkedHashMap<String, String> contact = new LinkedHashMap<>();
    public String comment = "";

    public ModelAuthor() {}

    public ModelAuthor(String name) {
        this.name = name;
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/metadata/ModelMetadata.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class ModelMetadata {

    public String name = "";
    public String tips = "";
    public ModelLicense license = new ModelLicense();
    public List<ModelAuthor> authors = new ArrayList<>();
    public LinkedHashMap<String, String> link = new LinkedHashMap<>();
}

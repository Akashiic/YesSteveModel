/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/models/ModelFiles.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.models;

import com.google.gson.annotations.SerializedName;

public class ModelFiles {

    public PlayerModelFiles player;

    @Deprecated
    public ReplacedModelFiles arrow;

    public ModelFilesWarper<ReplacedModelFiles> projectiles = new ModelFilesWarper<>();
    public ModelFilesWarper<ReplacedModelFiles> vehicles = new ModelFilesWarper<>();

    @SerializedName("sound_path")
    public String soundPath;

    @SerializedName("function_path")
    public String functionPath;

    @SerializedName("language_path")
    public String languagePath;
}

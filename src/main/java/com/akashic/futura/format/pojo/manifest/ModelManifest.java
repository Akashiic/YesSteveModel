/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/ModelManifest.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.akashic.futura.format.pojo.manifest.metadata.ModelMetadata;
import com.akashic.futura.format.pojo.manifest.models.ModelFiles;
import com.akashic.futura.format.pojo.manifest.models.ModelFilesWarper;
import com.akashic.futura.format.pojo.manifest.models.PlayerModelFiles;
import com.akashic.futura.format.pojo.manifest.settings.ModelProperties;

public class ModelManifest {

    public ModelMetadata metadata;
    public ModelProperties properties = new ModelProperties();
    public ModelFiles files = new ModelFiles();

    public void normalize() {
        if (properties == null) {
            properties = new ModelProperties();
        }
        if (properties.extraAnimation == null) {
            properties.extraAnimation = new LinkedHashMap<>();
        }
        if (properties.extraAnimationButtons == null) {
            properties.extraAnimationButtons = new ArrayList<>();
        }
        if (properties.extraAnimationClassify == null) {
            properties.extraAnimationClassify = new ArrayList<>();
        }
        if (files == null) {
            files = new ModelFiles();
        }
        if (files.player == null) {
            files.player = new PlayerModelFiles();
        }
        if (files.projectiles == null) {
            files.projectiles = new ModelFilesWarper<>();
        }
        if (files.projectiles.list == null) {
            files.projectiles.list = new ArrayList<>();
        }
        if (files.vehicles == null) {
            files.vehicles = new ModelFilesWarper<>();
        }
        if (files.vehicles.list == null) {
            files.vehicles.list = new ArrayList<>();
        }
        if (files.player.model == null) {
            files.player.model = new LinkedHashMap<>();
        }
        if (files.player.animation == null) {
            files.player.animation = new LinkedHashMap<>();
        }
        if (files.player.animationControllers == null) {
            files.player.animationControllers = new ArrayList<>();
        }
        if (files.player.texture == null) {
            files.player.texture = new ArrayList<>();
        }
    }
}

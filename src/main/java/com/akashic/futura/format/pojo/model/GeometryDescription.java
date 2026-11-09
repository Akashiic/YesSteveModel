/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/model/GeometryDescription.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.model;

import com.google.gson.annotations.SerializedName;

public class GeometryDescription {

    public String identifier = "";

    @SerializedName("texture_height")
    public float textureHeight;

    @SerializedName("texture_width")
    public float textureWidth;

    @SerializedName("visible_bounds_height")
    public float visibleBoundsHeight;

    @SerializedName("visible_bounds_width")
    public float visibleBoundsWidth;

    @SerializedName("visible_bounds_offset")
    public float[] visibleBoundsOffset;

    @SerializedName("ysm_height_scale")
    public float ysmHeightScale = 0.7f;

    @SerializedName("ysm_width_scale")
    public float ysmWidthScale = 0.7f;

    @SerializedName("ysm_extra_info")
    public ExtraInfo ysmExtraInfo;
}

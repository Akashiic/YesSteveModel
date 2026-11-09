/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/model/FaceUv.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.model;

import com.google.gson.annotations.SerializedName;

public class FaceUv {

    public float[] uv;

    @SerializedName("uv_size")
    public float[] uvSize;
    /*
     * // 暂未实现
     * @SerializedName("uv_rotation")
     * public Integer uvRotation;
     */
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/model/Bone.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs; the Bedrock {@code poly_mesh} field (for models imported from legacy .ysm files).
 */
package com.akashic.futura.format.pojo.model;

import java.util.ArrayList;

import com.google.gson.annotations.SerializedName;

public class Bone {

    public ArrayList<Cube> cubes;
    public boolean debug;
    public float inflate;
    public boolean mirror;
    public String name;
    public String parent;
    public float[] pivot;

    public float[] rotation;

    @SerializedName("poly_mesh")
    public PolyMesh polyMesh;
}

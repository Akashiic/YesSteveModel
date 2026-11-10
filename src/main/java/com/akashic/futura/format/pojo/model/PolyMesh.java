package com.akashic.futura.format.pojo.model;

import com.google.gson.annotations.SerializedName;

/**
 * A Bedrock {@code poly_mesh} of a bone: shared positions, normals and UVs, and polygons as {@code [position, normal,
 * uv]} index triples. YSM's open format has no meshes; the legacy {@code .ysm} importer writes one per bone to carry
 * the baked quads of a legacy model (see {@code native/src/exporter.cc}). Units are Bedrock's (pixels, x not negated);
 * UVs are measured from the top of the texture, as cube UVs are.
 */
public class PolyMesh {

    @SerializedName("normalized_uvs")
    public boolean normalizedUvs;

    public float[][] positions;
    public float[][] normals;
    public float[][] uvs;
    /** Polygons of three or four vertices, each vertex {@code [position, normal, uv]}. */
    public int[][][] polys;

    /**
     * Not Bedrock: how many polygons each cube of the legacy model had, in order, so that the quads can be regrouped
     * into cubes for back-face culling. Absent: every polygon is its own open cube.
     */
    @SerializedName("futura3d_cube_faces")
    public int[] cubeFaces;
}

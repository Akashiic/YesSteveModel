package com.akashic.futura.geo;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A Bedrock geometry ready to draw: every bone with its pivot, rest rotation and quads in model space (blocks, x
 * negated as upstream does), ordered so that a parent always comes before its children. No Minecraft classes.
 */
public final class BakedGeometry {

    /** Floats per quad: four vertices of (x, y, z, u, v), then the face normal (x, y, z). */
    public static final int QUAD_FLOATS = 4 * 5 + 3;

    /** A cube that may not cull faces: a missing face, a zero or mixed-sign size. */
    public static final byte SHAPE_OPEN = 0;
    /** All six faces, size positive on the three axes: back faces may be culled when fully opaque. */
    public static final byte SHAPE_POSITIVE = 1;
    /**
     * All six faces, size negative on the three axes: the winding is mirrored, so back-face culling hides the faces
     * toward the viewer (upstream's front-face-culled outline shells).
     */
    public static final byte SHAPE_NEGATIVE = 2;

    /** One bone. */
    public static final class Bone {

        public final String name;
        /** Index of the parent bone in {@link BakedGeometry#bones}, or -1 for a root. */
        public final int parent;
        /** Pivot in blocks, x negated. */
        public final float[] pivot;
        /** Rest rotation in radians, applied as Rz·Ry·Rx (x and y negated from the file). */
        public final float[] rotation;
        /** The bone's own quads, {@link #QUAD_FLOATS} floats each, rotation of each cube already applied. */
        public final float[] quads;
        /** Indices of the child bones. */
        public final int[] children;
        /** For each quad, the index of its cube among this bone's cubes. */
        public final int[] cubeOfQuad;
        /** For each cube of this bone, its {@code SHAPE_*} (decides face culling). */
        public final byte[] cubeShapes;

        Bone(String name, int parent, float[] pivot, float[] rotation, float[] quads, int[] children, int[] cubeOfQuad,
            byte[] cubeShapes) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.rotation = rotation;
            this.quads = quads;
            this.children = children;
            this.cubeOfQuad = cubeOfQuad;
            this.cubeShapes = cubeShapes;
        }

        public int quadCount() {
            return quads.length / QUAD_FLOATS;
        }

        /** Bones whose name starts with {@code ysmGlow} draw at full brightness (not inherited by children). */
        public boolean glow() {
            return name.startsWith("ysmGlow");
        }
    }

    public final float textureWidth;
    public final float textureHeight;
    public final Bone[] bones;
    /** Indices of the root bones. */
    public final int[] roots;
    public final int cubeCount;
    private final Map<String, Integer> byName;

    BakedGeometry(float textureWidth, float textureHeight, Bone[] bones, int[] roots, int cubeCount) {
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.bones = bones;
        this.roots = roots;
        this.cubeCount = cubeCount;
        Map<String, Integer> names = new HashMap<>();
        for (int i = 0; i < bones.length; i++) {
            names.putIfAbsent(bones[i].name, i);
        }
        this.byName = Collections.unmodifiableMap(names);
    }

    /** @return the bone's index, or -1 */
    public int indexOf(String bone) {
        Integer index = byName.get(bone);
        return index == null ? -1 : index;
    }

    public int quadCount() {
        int quads = 0;
        for (Bone bone : bones) {
            quads += bone.quadCount();
        }
        return quads;
    }
}

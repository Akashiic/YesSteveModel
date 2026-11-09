/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/GeoBuilder.java, Apache License 2.0.
 * Changes: builds plain float arrays instead of protobuf messages; bones ordered parents first; Java 8.
 */
package com.akashic.futura.geo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.pojo.model.Bone;
import com.akashic.futura.format.pojo.model.Cube;
import com.akashic.futura.format.pojo.model.FaceUv;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.akashic.futura.format.pojo.model.Geometry;
import com.akashic.futura.format.pojo.model.GeometryDescription;
import com.akashic.futura.format.pojo.model.UvFaces;

/**
 * Turns a parsed Bedrock geometry into a {@link BakedGeometry}, with the same conventions as upstream: x negated,
 * positions in blocks, cube rotation baked into the vertices, box or per-face UV normalised by the texture size this
 * geometry file declares, mirror and inflate.
 */
public final class GeoBaker {

    private GeoBaker() {}

    public static BakedGeometry bake(GeoModel raw) throws IOException {
        if (raw == null || raw.minecraftGeometry == null || raw.minecraftGeometry.isEmpty()) {
            throw new IOException("Invalid geo model: no minecraft:geometry");
        }
        Geometry geometry = raw.minecraftGeometry.get(0);
        GeometryDescription desc = geometry.description;
        float texWidth = desc != null && desc.textureWidth != 0 ? desc.textureWidth : 64f;
        float texHeight = desc != null && desc.textureHeight != 0 ? desc.textureHeight : 64f;
        List<Bone> raws = geometry.bones == null ? new ArrayList<Bone>() : geometry.bones;

        // Index by name (the first bone of a name wins), then order parents before children.
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < raws.size(); i++) {
            Bone bone = raws.get(i);
            if (bone == null || bone.name == null) {
                throw new IOException("Invalid geo model: bone " + i + " has no name");
            }
            byName.putIfAbsent(bone.name, i);
        }
        int[] parentOf = new int[raws.size()];
        for (int i = 0; i < raws.size(); i++) {
            String parent = raws.get(i).parent;
            Integer p = parent == null ? null : byName.get(parent);
            parentOf[i] = p == null || p == i ? -1 : p;
        }
        List<Integer> order = new ArrayList<>();
        int[] state = new int[raws.size()]; // 0 new, 1 visiting, 2 placed
        for (int i = 0; i < raws.size(); i++) {
            place(i, parentOf, state, order);
        }
        int[] newIndex = new int[raws.size()];
        for (int i = 0; i < order.size(); i++) {
            newIndex[order.get(i)] = i;
        }

        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            children.add(new ArrayList<Integer>());
        }
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            int p = parentOf[order.get(i)];
            if (p < 0) {
                roots.add(i);
            } else {
                children.get(newIndex[p])
                    .add(i);
            }
        }

        BakedGeometry.Bone[] bones = new BakedGeometry.Bone[order.size()];
        int cubes = 0;
        for (int i = 0; i < order.size(); i++) {
            Bone src = raws.get(order.get(i));
            float[] pivot = arrayOrDefault(src.pivot, 0, 0, 0);
            float[] rotation = arrayOrDefault(src.rotation, 0, 0, 0);
            QuadBuffer quads = new QuadBuffer();
            if (src.cubes != null) {
                for (Cube cube : src.cubes) {
                    if (cube != null) {
                        bakeCube(cube, texWidth, texHeight, src.inflate / 16f, src.mirror, quads);
                        cubes++;
                    }
                }
            }
            int p = parentOf[order.get(i)];
            bones[i] = new BakedGeometry.Bone(
                src.name,
                p < 0 ? -1 : newIndex[p],
                new float[] { -pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f },
                new float[] { (float) -Math.toRadians(rotation[0]), (float) -Math.toRadians(rotation[1]),
                    (float) Math.toRadians(rotation[2]) },
                quads.toArray(),
                toIntArray(children.get(i)));
        }
        return new BakedGeometry(texWidth, texHeight, bones, toIntArray(roots), cubes);
    }

    /** Depth-first placement; a parent cycle is broken by treating the bone that closes it as a root. */
    private static void place(int i, int[] parentOf, int[] state, List<Integer> order) {
        if (state[i] == 2) {
            return;
        }
        if (state[i] == 1) {
            return;
        }
        state[i] = 1;
        int p = parentOf[i];
        if (p >= 0) {
            if (state[p] == 1) {
                parentOf[i] = -1; // cycle
            } else {
                place(p, parentOf, state, order);
            }
        }
        state[i] = 2;
        order.add(i);
    }

    private static void bakeCube(Cube cube, float texWidth, float texHeight, float boneInflate, boolean boneMirror,
        QuadBuffer out) {
        float[] sizeIn = arrayOrDefault(cube.size, 1, 1, 1);
        float[] originIn = arrayOrDefault(cube.origin, 0, 0, 0);
        float inflate = cube.inflate != null ? cube.inflate / 16f : boneInflate;

        float sx = sizeIn[0] / 16f;
        float sy = sizeIn[1] / 16f;
        float sz = sizeIn[2] / 16f;
        float ox = -(originIn[0] + sizeIn[0]) / 16f;
        float oy = originIn[1] / 16f;
        float oz = originIn[2] / 16f;

        float[] p1 = { ox - inflate, oy - inflate, oz - inflate };
        float[] p2 = { ox - inflate, oy - inflate, oz + inflate + sz };
        float[] p3 = { ox - inflate, oy + inflate + sy, oz - inflate };
        float[] p4 = { ox - inflate, oy + inflate + sy, oz + inflate + sz };
        float[] p5 = { ox + inflate + sx, oy - inflate, oz - inflate };
        float[] p6 = { ox + inflate + sx, oy - inflate, oz + inflate + sz };
        float[] p7 = { ox + inflate + sx, oy + inflate + sy, oz - inflate };
        float[] p8 = { ox + inflate + sx, oy + inflate + sy, oz + inflate + sz };

        int start = out.size();
        boolean mirroredLayout = boneMirror || cube.mirror;
        if (cube.uv != null && cube.uv.perFaceUv != null) {
            perFace(
                out,
                cube.uv.perFaceUv,
                cube.mirror,
                mirroredLayout,
                texWidth,
                texHeight,
                p1,
                p2,
                p3,
                p4,
                p5,
                p6,
                p7,
                p8);
        } else if (cube.uv != null && cube.uv.boxUv != null && cube.uv.boxUv.length >= 2) {
            box(
                out,
                cube.uv.boxUv,
                sizeIn,
                cube.mirror,
                mirroredLayout,
                texWidth,
                texHeight,
                p1,
                p2,
                p3,
                p4,
                p5,
                p6,
                p7,
                p8);
        }

        float[] rotation = arrayOrDefault(cube.rotation, 0, 0, 0);
        if (rotation[0] != 0 || rotation[1] != 0 || rotation[2] != 0) {
            float[] pivotIn = arrayOrDefault(cube.pivot, 0, 0, 0);
            out.rotate(
                start,
                new float[] { -pivotIn[0] / 16f, pivotIn[1] / 16f, pivotIn[2] / 16f },
                -Math.toRadians(rotation[0]),
                -Math.toRadians(rotation[1]),
                Math.toRadians(rotation[2]));
        }
    }

    private static void perFace(QuadBuffer out, UvFaces faces, boolean cubeMirror, boolean mirroredLayout, float tw,
        float th, float[] p1, float[] p2, float[] p3, float[] p4, float[] p5, float[] p6, float[] p7, float[] p8) {
        if (!mirroredLayout) {
            face(out, faces.west, cubeMirror, Dir.WEST, tw, th, p4, p3, p1, p2);
            face(out, faces.east, cubeMirror, Dir.EAST, tw, th, p7, p8, p6, p5);
            face(out, faces.north, cubeMirror, Dir.NORTH, tw, th, p3, p7, p5, p1);
            face(out, faces.south, cubeMirror, Dir.SOUTH, tw, th, p8, p4, p2, p6);
            face(out, faces.up, cubeMirror, Dir.UP, tw, th, p4, p8, p7, p3);
            face(out, faces.down, cubeMirror, Dir.DOWN, tw, th, p1, p5, p6, p2);
        } else {
            face(out, faces.west, cubeMirror, Dir.WEST, tw, th, p7, p8, p6, p5);
            face(out, faces.east, cubeMirror, Dir.EAST, tw, th, p4, p3, p1, p2);
            face(out, faces.north, cubeMirror, Dir.NORTH, tw, th, p3, p7, p5, p1);
            face(out, faces.south, cubeMirror, Dir.SOUTH, tw, th, p8, p4, p2, p6);
            face(out, faces.up, cubeMirror, Dir.UP, tw, th, p1, p5, p6, p2);
            face(out, faces.down, cubeMirror, Dir.DOWN, tw, th, p4, p8, p7, p3);
        }
    }

    private static void box(QuadBuffer out, float[] uv, float[] size, boolean cubeMirror, boolean mirroredLayout,
        float tw, float th, float[] p1, float[] p2, float[] p3, float[] p4, float[] p5, float[] p6, float[] p7,
        float[] p8) {
        float x = (float) Math.floor(size[0]);
        float y = (float) Math.floor(size[1]);
        float z = (float) Math.floor(size[2]);
        float u = uv[0];
        float v = uv[1];
        if (!mirroredLayout) {
            out.quad(p4, p3, p1, p2, u + z + x, v + z, z, y, tw, th, cubeMirror, Dir.WEST);
            out.quad(p7, p8, p6, p5, u, v + z, z, y, tw, th, cubeMirror, Dir.EAST);
        } else {
            out.quad(p7, p8, p6, p5, u + z + x, v + z, z, y, tw, th, cubeMirror, Dir.WEST);
            out.quad(p4, p3, p1, p2, u, v + z, z, y, tw, th, cubeMirror, Dir.EAST);
        }
        out.quad(p3, p7, p5, p1, u + z, v + z, x, y, tw, th, cubeMirror, Dir.NORTH);
        out.quad(p8, p4, p2, p6, u + z + x + z, v + z, x, y, tw, th, cubeMirror, Dir.SOUTH);
        out.quad(p4, p8, p7, p3, u + z, v, x, z, tw, th, cubeMirror, Dir.UP);
        out.quad(p1, p5, p6, p2, u + z + x, v + z, x, -z, tw, th, cubeMirror, Dir.DOWN);
    }

    private static void face(QuadBuffer out, FaceUv face, boolean cubeMirror, Dir dir, float tw, float th, float[] a,
        float[] b, float[] c, float[] d) {
        if (face == null || face.uv == null || face.uvSize == null || face.uv.length < 2 || face.uvSize.length < 2) {
            return;
        }
        out.quad(a, b, c, d, face.uv[0], face.uv[1], face.uvSize[0], face.uvSize[1], tw, th, cubeMirror, dir);
    }

    private static float[] arrayOrDefault(float[] values, float x, float y, float z) {
        if (values == null || values.length < 3) {
            return new float[] { x, y, z };
        }
        return values;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    private enum Dir {

        DOWN(0, -1, 0),
        UP(0, 1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0);

        final float x;
        final float y;
        final float z;

        Dir(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A growable float array of quads in the {@link BakedGeometry#QUAD_FLOATS} layout. */
    private static final class QuadBuffer {

        private float[] data = new float[BakedGeometry.QUAD_FLOATS * 6];
        private int length;

        int size() {
            return length;
        }

        void quad(float[] a, float[] b, float[] c, float[] d, float u1, float v1, float uSize, float vSize, float tw,
            float th, boolean mirror, Dir dir) {
            float u2 = (u1 + uSize) / tw;
            float v2 = (v1 + vSize) / th;
            u1 /= tw;
            v1 /= th;
            float[][] uv = mirror ? new float[][] { { u1, v1 }, { u2, v1 }, { u2, v2 }, { u1, v2 } }
                : new float[][] { { u2, v1 }, { u1, v1 }, { u1, v2 }, { u2, v2 } };
            float[][] vertices = { a, b, c, d };
            ensure(BakedGeometry.QUAD_FLOATS);
            for (int i = 0; i < 4; i++) {
                data[length++] = vertices[i][0];
                data[length++] = vertices[i][1];
                data[length++] = vertices[i][2];
                data[length++] = uv[i][0];
                data[length++] = uv[i][1];
            }
            data[length++] = mirror ? -dir.x : dir.x;
            data[length++] = dir.y;
            data[length++] = dir.z;
        }

        /** Rotates the quads from {@code start} on around {@code pivot}: X, then Y, then Z, as upstream. */
        void rotate(int start, float[] pivot, double rx, double ry, double rz) {
            for (int q = start; q < length; q += BakedGeometry.QUAD_FLOATS) {
                for (int i = 0; i < 4; i++) {
                    int o = q + i * 5;
                    rotateInPlace(data, o, pivot, rx, ry, rz);
                }
                rotateInPlace(data, q + 20, null, rx, ry, rz);
            }
        }

        private static void rotateInPlace(float[] d, int o, float[] pivot, double rx, double ry, double rz) {
            double px = pivot == null ? 0 : pivot[0];
            double py = pivot == null ? 0 : pivot[1];
            double pz = pivot == null ? 0 : pivot[2];
            double x = d[o] - px;
            double y = d[o + 1] - py;
            double z = d[o + 2] - pz;

            double y1 = y * Math.cos(rx) - z * Math.sin(rx);
            double z1 = y * Math.sin(rx) + z * Math.cos(rx);

            double x2 = x * Math.cos(ry) + z1 * Math.sin(ry);
            double z2 = -x * Math.sin(ry) + z1 * Math.cos(ry);

            double x3 = x2 * Math.cos(rz) - y1 * Math.sin(rz);
            double y3 = x2 * Math.sin(rz) + y1 * Math.cos(rz);

            d[o] = (float) (x3 + px);
            d[o + 1] = (float) (y3 + py);
            d[o + 2] = (float) (z2 + pz);
        }

        private void ensure(int extra) {
            if (length + extra > data.length) {
                float[] grown = new float[Math.max(data.length * 2, length + extra)];
                System.arraycopy(data, 0, grown, 0, length);
                data = grown;
            }
        }

        float[] toArray() {
            float[] array = new float[length];
            System.arraycopy(data, 0, array, 0, length);
            return array;
        }
    }
}

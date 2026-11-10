/*
 * Adapted from Yes Steve Model (native renderer, YesSteveModel-Native), Apache License 2.0:
 * modules/gfx/src/renderer/render_state_impl.inc (ComputeFacingCoefficient),
 * modules/gfx/src/renderer/cube/transform_common_impl.inc (back-face test and face depth of TransformQuadAttributes),
 * modules/gfx/src/renderer/buffer/quad_ref.h (NdcDepthSortKey) and
 * modules/gfx/src/renderer/buffer/translucent.h (TranslucentProxyBuffer::Flush).
 * Changes: Java; quads transformed to model space with the bone matrices of PoseMatrices, back faces tested on the
 * transformed winding (the same sign as upstream's plane times winding sign); normals left unflipped for GL's
 * two-sided lighting.
 */
package com.akashic.futura.geo;

import java.util.Arrays;

/**
 * Upstream's per-draw ordering of translucent quads, without Minecraft classes. Every quad of the two translucent
 * partitions is transformed to model space with its bone's matrix, the back faces of
 * {@link FacePartition#TRANSLUCENT_CULL} quads are dropped, and the rest are sorted back to front by the
 * normalised-device depth of the quad's centre ({@code clip_z / clip_w}), ties kept in emission order: the
 * {@link FacePartition#TRANSLUCENT} quads, then the {@link FacePartition#TRANSLUCENT_CULL} ones, each in bone
 * pre-order, cube order and face order. Upstream sorts every quad of one model draw in one list this way; quads of
 * different draws (other players, other models) are not sorted against each other, there or here.
 *
 * <p>
 * The back-face test is OpenGL's: a quad is a back face when its first three vertices wind clockwise on screen
 * (counter-clockwise front faces, the default). It is computed from the model-to-clip matrix as upstream does, with
 * the homogeneous eye point given by the cofactors of the matrix's x, y and w rows, so that perspective, orthographic
 * (inventory) and mirrored projections all agree with what the GPU would cull.
 */
public final class TranslucentSort {

    /** Floats per quad of local data: four positions, then the centre, then the face normal. */
    private static final int LOCAL = 18;

    private final BakedGeometry geometry;
    /** Number of translucent quads. */
    public final int count;
    private final int[] bone;
    private final boolean[] culled;
    private final float[] local;
    /** {@link FacePartition#UV_FLOATS} per quad, in emission order. */
    public final float[] uv;

    /** Per quad, its four positions in model space after the last {@link #sort}. */
    public final float[] positions;
    /** Per quad, its unit normal in model space after the last {@link #sort} (not flipped for back faces). */
    public final float[] normals;
    private final int[] order;
    private final long[] keys;
    private final float[] normalMatrices;
    private final int[] normalStamp;
    private int stamp;
    private int drawn;

    /**
     * @param partitions per bone, per quad, from {@link FacePartition#classify}
     * @param uvs        per bone, the UVs to draw ({@link FacePartition.Baked#uvs}), or {@code null} for the baked ones
     */
    public TranslucentSort(BakedGeometry geometry, byte[][] partitions, float[][] uvs) {
        this.geometry = geometry;
        int[] bones = preorder(geometry);
        int n = 0;
        for (int b : bones) {
            for (byte p : partitions[b]) {
                n += FacePartition.translucent(p) ? 1 : 0;
            }
        }
        count = n;
        bone = new int[n];
        culled = new boolean[n];
        local = new float[n * LOCAL];
        uv = new float[n * FacePartition.UV_FLOATS];
        int i = 0;
        for (int pass : new int[] { FacePartition.TRANSLUCENT, FacePartition.TRANSLUCENT_CULL }) {
            for (int b : bones) {
                BakedGeometry.Bone src = geometry.bones[b];
                float[] boneUv = uvs == null ? null : uvs[b];
                for (int q = 0; q < partitions[b].length; q++) {
                    if (partitions[b][q] != pass) {
                        continue;
                    }
                    bone[i] = b;
                    culled[i] = pass == FacePartition.TRANSLUCENT_CULL;
                    int s = q * BakedGeometry.QUAD_FLOATS, l = i * LOCAL;
                    for (int v = 0; v < 4; v++) {
                        for (int k = 0; k < 3; k++) {
                            float x = src.quads[s + v * 5 + k];
                            local[l + v * 3 + k] = x;
                            local[l + 12 + k] += x * 0.25f;
                        }
                        uv[i * 8 + v * 2] = boneUv == null ? src.quads[s + v * 5 + 3] : boneUv[q * 8 + v * 2];
                        uv[i * 8 + v * 2 + 1] = boneUv == null ? src.quads[s + v * 5 + 4] : boneUv[q * 8 + v * 2 + 1];
                    }
                    for (int k = 0; k < 3; k++) {
                        local[l + 15 + k] = src.quads[s + 20 + k];
                    }
                    i++;
                }
            }
        }
        positions = new float[n * 12];
        normals = new float[n * 3];
        order = new int[n];
        keys = new long[n];
        normalMatrices = new float[geometry.bones.length * 9];
        normalStamp = new int[geometry.bones.length];
    }

    /** Bones in depth-first pre-order from the roots (upstream's sorted bone order). */
    static int[] preorder(BakedGeometry geometry) {
        int[] out = new int[geometry.bones.length];
        int[] stack = new int[geometry.bones.length];
        boolean[] seen = new boolean[geometry.bones.length];
        int n = 0;
        for (int root : geometry.roots) {
            int top = 0;
            stack[top++] = root;
            while (top > 0) {
                int b = stack[--top];
                if (seen[b]) {
                    continue;
                }
                seen[b] = true;
                out[n++] = b;
                int[] children = geometry.bones[b].children;
                for (int c = children.length - 1; c >= 0; c--) {
                    if (!seen[children[c]] && top < stack.length) {
                        stack[top++] = children[c];
                    }
                }
            }
        }
        // Anything unreachable from a root (not expected) keeps its index order.
        for (int b = 0; b < seen.length && n < out.length; b++) {
            if (!seen[b]) {
                out[n++] = b;
            }
        }
        return out;
    }

    /** The bone of a quad. */
    public int bone(int quad) {
        return bone[quad];
    }

    /** Whether a quad is in {@link FacePartition#TRANSLUCENT_CULL}. */
    public boolean culled(int quad) {
        return culled[quad];
    }

    /** Number of quads to draw after the last {@link #sort}. */
    public int drawn() {
        return drawn;
    }

    /** The quad drawn in position {@code i} (0: the farthest) after the last {@link #sort}. */
    public int order(int i) {
        return order[i];
    }

    /**
     * Transforms, culls and sorts the quads of visible bones.
     *
     * @param pose the bone matrices of this frame
     * @param clip the model-space-to-clip matrix (projection times model-view), column-major
     * @return the number of quads to draw
     */
    public int sort(PoseMatrices pose, float[] clip) {
        return sort(pose, clip, true);
    }

    /**
     * {@link #sort}, or with {@code byDepth} false the same quads in emission order (for comparisons: what a fixed
     * order draws).
     */
    public int sort(PoseMatrices pose, float[] clip, boolean byDepth) {
        float[] eye = eye(clip);
        float[] m = pose.bones;
        stamp++;
        int n = 0;
        for (int q = 0; q < count; q++) {
            int b = bone[q];
            if (!pose.visible(b)) {
                continue;
            }
            int o = b * Mat4.FLOATS, l = q * LOCAL, p = q * 12;
            for (int v = 0; v < 4; v++) {
                float x = local[l + v * 3], y = local[l + v * 3 + 1], z = local[l + v * 3 + 2];
                for (int r = 0; r < 3; r++) {
                    positions[p + v * 3 + r] = m[o + r] * x + m[o + 4 + r] * y + m[o + 8 + r] * z + m[o + 12 + r];
                }
            }
            if (culled[q] && backFace(positions, p, eye)) {
                continue;
            }
            float cx = local[l + 12], cy = local[l + 13], cz = local[l + 14];
            float wx = m[o] * cx + m[o + 4] * cy + m[o + 8] * cz + m[o + 12];
            float wy = m[o + 1] * cx + m[o + 5] * cy + m[o + 9] * cz + m[o + 13];
            float wz = m[o + 2] * cx + m[o + 6] * cy + m[o + 10] * cz + m[o + 14];
            float clipZ = clip[2] * wx + clip[6] * wy + clip[10] * wz + clip[14];
            float clipW = clip[3] * wx + clip[7] * wy + clip[11] * wz + clip[15];
            keys[n++] = byDepth ? (long) (depthKey(clipZ / clipW) ^ 0x80000000) << 32 | q : q;
            normal(pose, b, q, l);
        }
        Arrays.sort(keys, 0, n);
        for (int i = 0; i < n; i++) {
            order[i] = (int) keys[i];
        }
        drawn = n;
        return n;
    }

    private void normal(PoseMatrices pose, int b, int q, int l) {
        int o = b * 9;
        if (normalStamp[b] != stamp) {
            normalStamp[b] = stamp;
            normalMatrix(pose.bones, b * Mat4.FLOATS, normalMatrices, o);
        }
        float[] n = normalMatrices;
        float x = local[l + 15], y = local[l + 16], z = local[l + 17];
        float nx = n[o] * x + n[o + 3] * y + n[o + 6] * z;
        float ny = n[o + 1] * x + n[o + 4] * y + n[o + 7] * z;
        float nz = n[o + 2] * x + n[o + 5] * y + n[o + 8] * z;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0 && !Float.isInfinite(len)) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        normals[q * 3] = nx;
        normals[q * 3 + 1] = ny;
        normals[q * 3 + 2] = nz;
    }

    /**
     * The inverse transpose of a matrix's linear part, up to a positive factor (column-major 3x3): the columns of
     * {@code A^-T} are {@code c1×c2}, {@code c2×c0} and {@code c0×c1} over the determinant, whose sign is kept.
     */
    static void normalMatrix(float[] m, int o, float[] out, int d) {
        float ax = m[o], ay = m[o + 1], az = m[o + 2];
        float bx = m[o + 4], by = m[o + 5], bz = m[o + 6];
        float cx = m[o + 8], cy = m[o + 9], cz = m[o + 10];
        // b × c, c × a, a × b
        float x0 = by * cz - bz * cy, y0 = bz * cx - bx * cz, z0 = bx * cy - by * cx;
        float x1 = cy * az - cz * ay, y1 = cz * ax - cx * az, z1 = cx * ay - cy * ax;
        float x2 = ay * bz - az * by, y2 = az * bx - ax * bz, z2 = ax * by - ay * bx;
        float det = ax * x0 + ay * y0 + az * z0;
        float s = det < 0 ? -1 : 1;
        out[d] = s * x0;
        out[d + 1] = s * y0;
        out[d + 2] = s * z0;
        out[d + 3] = s * x1;
        out[d + 4] = s * y1;
        out[d + 5] = s * z1;
        out[d + 6] = s * x2;
        out[d + 7] = s * y2;
        out[d + 8] = s * z2;
    }

    /**
     * Whether the quad at {@code p} (four model-space positions) is a back face for the homogeneous eye point:
     * upstream's {@code facing <= 0}, with the face plane of the first three vertices.
     */
    static boolean backFace(float[] pos, int p, float[] eye) {
        float x0 = pos[p], y0 = pos[p + 1], z0 = pos[p + 2];
        float ax = pos[p + 3] - x0, ay = pos[p + 4] - y0, az = pos[p + 5] - z0;
        float bx = pos[p + 6] - x0, by = pos[p + 7] - y0, bz = pos[p + 8] - z0;
        float gx = ay * bz - az * by, gy = az * bx - ax * bz, gz = ax * by - ay * bx;
        float facing = gx * (eye[0] - x0 * eye[3]) + gy * (eye[1] - y0 * eye[3]) + gz * (eye[2] - z0 * eye[3]);
        return facing <= 0;
    }

    /**
     * upstream {@code ComputeFacingCoefficient}: the homogeneous point every ray of the projection goes through (the
     * eye, or a direction at infinity for an orthographic projection), signed so that a plane through counter-clockwise
     * winding on screen gives a positive dot product.
     */
    public static float[] eye(float[] clip) {
        float x0 = clip[0], x1 = clip[4], x2 = clip[8], x3 = clip[12];
        float y0 = clip[1], y1 = clip[5], y2 = clip[9], y3 = clip[13];
        float w0 = clip[3], w1 = clip[7], w2 = clip[11], w3 = clip[15];
        return new float[] { det3(x1, x2, x3, y1, y2, y3, w1, w2, w3), -det3(x0, x2, x3, y0, y2, y3, w0, w2, w3),
            det3(x0, x1, x3, y0, y1, y3, w0, w1, w3), -det3(x0, x1, x2, y0, y1, y2, w0, w1, w2) };
    }

    private static float det3(float a0, float a1, float a2, float b0, float b1, float b2, float c0, float c1,
        float c2) {
        return a0 * (b1 * c2 - b2 * c1) - a1 * (b0 * c2 - b2 * c0) + a2 * (b0 * c1 - b1 * c0);
    }

    /**
     * upstream {@code NdcDepthSortKey}: an unsigned key that sorts deeper (farther) depths first; non-finite depths
     * get the largest key (drawn last).
     */
    static int depthKey(float depth) {
        if (Float.isNaN(depth) || Float.isInfinite(depth)) {
            return 0xFFFFFFFF;
        }
        if (depth == 0) {
            depth = 0;
        }
        int bits = Float.floatToRawIntBits(depth);
        int ascending = bits ^ (bits < 0 ? 0xFFFFFFFF : 0x80000000);
        return ~ascending;
    }

    public BakedGeometry geometry() {
        return geometry;
    }
}

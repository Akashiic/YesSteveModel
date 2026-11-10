/*
 * Adapted from Yes Steve Model (native renderer, YesSteveModel-Native), modules/gfx/src/bake/baked_model.cc,
 * Apache License 2.0: the quad inspection
 * of QuadInspector::Process (UV processing of origin version 29, the one Yes Steve Model's Java side passes for every
 * model), the inverted-cube test of ProcessCube and the partition rules of ModelBaker::AddProcessedCube.
 * Changes: Java over the baked quads of BakedGeometry; force_translucent left out (Yes Steve Model never enables it).
 */
package com.akashic.futura.geo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * Splits each bone's quads into upstream's four logical partitions from the alpha of the base texture under each face
 * and the cube's shape, exactly as the native baker does. No Minecraft classes.
 *
 * <ul>
 * <li>Each quad's UVs are snapped to texels (u rounds up from 0.6 of a texel, v from 0.4) and the texels under them
 * give its type: all alpha 0 empty (left out), any alpha strictly between 0 and 255 translucent, alpha 0 and 255 only
 * cutout, all 255 solid. A rectangle with no width or height is widened by one texel (u to the right, v upward, so
 * that the last two vertices move up a texel), and the snapped UVs are the ones drawn.</li>
 * <li>Cutout and translucent quads go to a translucent partition, solid ones to an opaque partition.</li>
 * <li>A cube is <em>inverted</em> when it has six faces and some face's normal points toward the cube's centre (a
 * negative size on an odd number of axes, or a bone mirror). An inverted cube, or any six-face cube when
 * {@code all_cutout} ({@code force_culling}) is set, culls back faces in both partitions.</li>
 * <li>Otherwise a six-face cube whose six quads are all solid culls back faces; its other solid quads keep both sides,
 * and its translucent quads keep both sides.</li>
 * </ul>
 *
 * <p>
 * Culling is GL's winding test: upstream culls on the CPU with the face plane times the sign of the winding, which is
 * the same test (see {@link TranslucentSort}).
 */
public final class FacePartition {

    /** Opaque, back faces culled (upstream {@code cutout}). */
    public static final int OPAQUE_CULL = 0;
    /** Opaque, both sides drawn ({@code cutout_no_culling}). */
    public static final int OPAQUE = 1;
    /** Translucent, both sides drawn ({@code translucent}). */
    public static final int TRANSLUCENT = 2;
    /** Translucent, back faces culled ({@code translucent_culling}). */
    public static final int TRANSLUCENT_CULL = 3;
    public static final int COUNT = 4;
    /** Left out: nothing visible. */
    public static final byte NONE = -1;

    /** Quad types of upstream's {@code QuadType}. */
    public static final int TYPE_EMPTY = 0;
    public static final int TYPE_TRANSLUCENT = 1;
    public static final int TYPE_CUTOUT = 2;
    public static final int TYPE_SOLID = 3;

    /** Floats of processed UV per quad: (u, v) of the four vertices. */
    public static final int UV_FLOATS = 8;

    private FacePartition() {}

    public static boolean translucent(int partition) {
        return partition == TRANSLUCENT || partition == TRANSLUCENT_CULL;
    }

    public static boolean culled(int partition) {
        return partition == OPAQUE_CULL || partition == TRANSLUCENT_CULL;
    }

    /** The alpha channel of a texture, one byte per texel, rows top to bottom. */
    public static final class Alpha {

        public final int width;
        public final int height;
        public final byte[] values;

        public Alpha(int width, int height, byte[] values) {
            if (width <= 0 || height <= 0 || values.length < width * height) {
                throw new IllegalArgumentException("Bad alpha map " + width + "x" + height);
            }
            this.width = width;
            this.height = height;
            this.values = values;
        }

        int at(int x, int y) {
            return values[y * width + x] & 0xFF;
        }

        /** Reads a PNG (or any format ImageIO knows). */
        public static Alpha read(InputStream in) throws IOException {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("Unreadable image");
            }
            return of(image);
        }

        /** The alpha of a decoded image (the same image a model's texture is uploaded from). */
        public static Alpha of(BufferedImage image) {
            int w = image.getWidth(), h = image.getHeight();
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            byte[] alpha = new byte[w * h];
            for (int i = 0; i < alpha.length; i++) {
                alpha[i] = (byte) (argb[i] >>> 24);
            }
            return new Alpha(w, h, alpha);
        }
    }

    /** upstream {@code QuadInspector::Quantize}. */
    static int quantize(float value, int extent, float threshold) {
        float scaled = Math.max(0f, Math.min(1f, value)) * extent;
        int base = (int) scaled;
        if (base < extent && scaled - base >= threshold) {
            base++;
        }
        return Math.min(base, extent);
    }

    /**
     * The type of one quad, upstream's {@code QuadInspector::Process} for origin version 29 ({@code k254}).
     *
     * @param uv the (u, v) of the four vertices at {@code offset}, normalised; snapped in place as upstream draws them
     */
    public static int quadType(Alpha alpha, float[] uv, int offset) {
        int width = alpha.width, height = alpha.height;
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            int u = quantize(uv[offset + i * 2], width, 0.6f);
            int v = quantize(uv[offset + i * 2 + 1], height, 0.4f);
            uv[offset + i * 2] = (float) u / width;
            uv[offset + i * 2 + 1] = (float) v / height;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        if (minU == maxU) {
            if (maxU < width) {
                maxU++;
            } else if (minU > 0) {
                minU--;
                float offsetU = -1f / width;
                uv[offset] += offsetU;
                uv[offset + 6] += offsetU;
            }
        }
        if (minV == maxV) {
            if (minV > 0) {
                minV--;
                float offsetV = -1f / height;
                uv[offset + 5] += offsetV;
                uv[offset + 7] += offsetV;
            } else if (maxV < height) {
                maxV++;
            }
        }
        boolean empty = true, hasTransparent = false;
        for (int y = minV; y < maxV; y++) {
            for (int x = minU; x < maxU; x++) {
                int a = alpha.at(x, y);
                if (a != 0) {
                    empty = false;
                    if (a != 255) {
                        return TYPE_TRANSLUCENT;
                    }
                } else {
                    hasTransparent = true;
                }
            }
        }
        return empty ? TYPE_EMPTY : hasTransparent ? TYPE_CUTOUT : TYPE_SOLID;
    }

    /** The partitions of a geometry for one texture, and the UVs upstream draws with it. */
    public static final class Baked {

        /** Per bone, per quad, a partition or {@link #NONE}. */
        public final byte[][] partitions;
        /** Per bone, {@link #UV_FLOATS} per quad: the snapped UVs. */
        public final float[][] uvs;

        Baked(byte[][] partitions, float[][] uvs) {
            this.partitions = partitions;
            this.uvs = uvs;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Baked && Arrays.deepEquals(partitions, ((Baked) o).partitions)
                && Arrays.deepEquals(uvs, ((Baked) o).uvs);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(partitions) * 31 + Arrays.deepHashCode(uvs);
        }
    }

    /**
     * The partition of every quad of every bone.
     *
     * @param alpha        the base texture's alpha, or {@code null} to treat every face as solid (UVs unchanged)
     * @param forceCulling {@code properties.all_cutout}: every six-face cube culls back faces
     * @return per bone, per quad, a partition or {@link #NONE}
     */
    public static byte[][] classify(BakedGeometry geometry, Alpha alpha, boolean forceCulling) {
        return bake(geometry, alpha, forceCulling).partitions;
    }

    /** {@link #classify} with the snapped UVs. */
    public static Baked bake(BakedGeometry geometry, Alpha alpha, boolean forceCulling) {
        byte[][] partitions = new byte[geometry.bones.length][];
        float[][] uvs = new float[geometry.bones.length][];
        for (int b = 0; b < geometry.bones.length; b++) {
            BakedGeometry.Bone bone = geometry.bones[b];
            uvs[b] = sourceUvs(bone);
            partitions[b] = classify(bone, alpha, forceCulling, uvs[b]);
        }
        return new Baked(partitions, uvs);
    }

    /** The UVs of a bone's quads as baked, {@link #UV_FLOATS} per quad. */
    public static float[] sourceUvs(BakedGeometry.Bone bone) {
        int quads = bone.quadCount();
        float[] uv = new float[quads * UV_FLOATS];
        for (int q = 0; q < quads; q++) {
            for (int v = 0; v < 4; v++) {
                uv[q * UV_FLOATS + v * 2] = bone.quads[q * BakedGeometry.QUAD_FLOATS + v * 5 + 3];
                uv[q * UV_FLOATS + v * 2 + 1] = bone.quads[q * BakedGeometry.QUAD_FLOATS + v * 5 + 4];
            }
        }
        return uv;
    }

    static byte[] classify(BakedGeometry.Bone bone, Alpha alpha, boolean forceCulling, float[] uvs) {
        int quads = bone.quadCount();
        int cubes = bone.cubeShapes.length;
        int[] types = new int[quads];
        int[] faces = new int[cubes];
        int[] solid = new int[cubes];
        for (int q = 0; q < quads; q++) {
            int t = alpha == null ? TYPE_SOLID : quadType(alpha, uvs, q * UV_FLOATS);
            types[q] = t;
            int c = bone.cubeOfQuad[q];
            faces[c]++;
            if (t == TYPE_SOLID) {
                solid[c]++;
            }
        }
        boolean[] inverted = inverted(bone, faces);
        byte[] out = new byte[quads];
        for (int q = 0; q < quads; q++) {
            int c = bone.cubeOfQuad[q];
            int t = types[q];
            if (t == TYPE_EMPTY) {
                out[q] = NONE;
                continue;
            }
            boolean full = faces[c] == 6;
            boolean translucent = t != TYPE_SOLID;
            if (forceCulling && full || inverted[c]) {
                out[q] = (byte) (translucent ? TRANSLUCENT_CULL : OPAQUE_CULL);
            } else if (translucent) {
                out[q] = TRANSLUCENT;
            } else {
                out[q] = (byte) (solid[c] == 6 ? OPAQUE_CULL : OPAQUE);
            }
        }
        return out;
    }

    /**
     * upstream {@code ProcessCube}: a six-face cube is inverted when some face's normal points against the vector from
     * the cube's centre (the mean of the face centres) to the face's centre.
     */
    static boolean[] inverted(BakedGeometry.Bone bone, int[] faces) {
        int cubes = faces.length;
        float[] centre = new float[cubes * 3];
        float[] q = bone.quads;
        int quads = bone.quadCount();
        for (int i = 0; i < quads; i++) {
            int c = bone.cubeOfQuad[i];
            if (faces[c] != 6) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                centre[c * 3 + k] += faceCentre(q, i, k) / 6;
            }
        }
        boolean[] out = new boolean[cubes];
        for (int i = 0; i < quads; i++) {
            int c = bone.cubeOfQuad[i];
            if (faces[c] != 6 || out[c]) {
                continue;
            }
            int o = i * BakedGeometry.QUAD_FLOATS;
            float lengthSq = 0, dot = 0;
            for (int k = 0; k < 3; k++) {
                float outward = faceCentre(q, i, k) - centre[c * 3 + k];
                lengthSq += outward * outward;
                dot += q[o + 20 + k] * outward;
            }
            if (lengthSq > 1e-12f && dot < 0) {
                out[c] = true;
            }
        }
        return out;
    }

    private static float faceCentre(float[] q, int quad, int axis) {
        int o = quad * BakedGeometry.QUAD_FLOATS + axis;
        float sum = 0;
        for (int v = 0; v < 4; v++) {
            sum += q[o + v * 5] * 0.25f;
        }
        return sum;
    }
}

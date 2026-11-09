package com.akashic.futura.geo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

/**
 * Splits each bone's quads into upstream's four logical partitions from the alpha of the base texture under each face
 * and the cube's shape ({@code docs/upstream/architecture/rendering/implementation/bake-and-partition.md} and the
 * visible-face table of {@code architecture/rendering/overview.md}). No Minecraft classes.
 *
 * <ul>
 * <li>A face whose texels are all fully transparent is left out.</li>
 * <li>A face with any texel below full alpha (holes included) is translucent, the rest opaque.</li>
 * <li>A complete positive-size cube whose six faces are all opaque culls back faces; a complete negative-size cube
 * always culls (its mirrored winding makes the culled faces the ones toward the viewer); anything else keeps both
 * sides.</li>
 * </ul>
 */
public final class FacePartition {

    /** Opaque, back faces culled. */
    public static final int OPAQUE_CULL = 0;
    /** Opaque, both sides drawn. */
    public static final int OPAQUE = 1;
    /** Translucent, both sides drawn. */
    public static final int TRANSLUCENT = 2;
    /** Translucent, back faces culled (complete negative-size cubes). */
    public static final int TRANSLUCENT_CULL = 3;
    public static final int COUNT = 4;
    /** Left out: nothing visible. */
    public static final byte NONE = -1;

    /** Alpha classes of one face. */
    public static final int ALPHA_OPAQUE = 0;
    public static final int ALPHA_TRANSLUCENT = 1;
    public static final int ALPHA_EMPTY = 2;

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
            int w = image.getWidth(), h = image.getHeight();
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            byte[] alpha = new byte[w * h];
            for (int i = 0; i < alpha.length; i++) {
                alpha[i] = (byte) (argb[i] >>> 24);
            }
            return new Alpha(w, h, alpha);
        }
    }

    /**
     * Classifies the texels under a UV rectangle (normalised coordinates, any corner order): a texel counts when its
     * centre lies inside; a rectangle thinner than a texel samples the texel it starts in. Coordinates outside the
     * texture are clamped.
     */
    public static int alphaClass(Alpha alpha, float u0, float v0, float u1, float v1) {
        int x0 = first(Math.min(u0, u1) * alpha.width, alpha.width);
        int x1 = last(Math.max(u0, u1) * alpha.width, x0, alpha.width);
        int y0 = first(Math.min(v0, v1) * alpha.height, alpha.height);
        int y1 = last(Math.max(v0, v1) * alpha.height, y0, alpha.height);
        boolean any = false, all = true;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int a = alpha.at(x, y);
                if (a != 0) {
                    any = true;
                }
                if (a != 255) {
                    all = false;
                }
            }
        }
        return !any ? ALPHA_EMPTY : all ? ALPHA_OPAQUE : ALPHA_TRANSLUCENT;
    }

    private static int first(float min, int size) {
        int i = (int) Math.floor(min + 1e-3f);
        return Math.max(0, Math.min(size - 1, i));
    }

    private static int last(float max, int first, int size) {
        int i = (int) Math.ceil(max - 1e-3f) - 1;
        return Math.max(first, Math.min(size - 1, i));
    }

    /** The alpha class of one baked quad, from the bounds of its four UVs. */
    public static int alphaClass(Alpha alpha, float[] quads, int offset) {
        float uMin = Float.MAX_VALUE, vMin = Float.MAX_VALUE, uMax = -Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            float u = quads[offset + v * 5 + 3], w = quads[offset + v * 5 + 4];
            uMin = Math.min(uMin, u);
            uMax = Math.max(uMax, u);
            vMin = Math.min(vMin, w);
            vMax = Math.max(vMax, w);
        }
        return alphaClass(alpha, uMin, vMin, uMax, vMax);
    }

    /**
     * The partition of every quad of every bone.
     *
     * @param alpha       the base texture's alpha, or {@code null} to treat every face as opaque
     * @param forceOpaque {@code properties.all_cutout}: every face that is not fully transparent is opaque
     * @return per bone, per quad, a partition or {@link #NONE}
     */
    public static byte[][] classify(BakedGeometry geometry, Alpha alpha, boolean forceOpaque) {
        byte[][] out = new byte[geometry.bones.length][];
        for (int b = 0; b < geometry.bones.length; b++) {
            out[b] = classify(geometry.bones[b], alpha, forceOpaque);
        }
        return out;
    }

    static byte[] classify(BakedGeometry.Bone bone, Alpha alpha, boolean forceOpaque) {
        int quads = bone.quadCount();
        int[] classes = new int[quads];
        boolean[] cubeOpaque = new boolean[bone.cubeShapes.length];
        java.util.Arrays.fill(cubeOpaque, true);
        for (int q = 0; q < quads; q++) {
            int c = alpha == null ? ALPHA_OPAQUE : alphaClass(alpha, bone.quads, q * BakedGeometry.QUAD_FLOATS);
            if (forceOpaque && c == ALPHA_TRANSLUCENT) {
                c = ALPHA_OPAQUE;
            }
            classes[q] = c;
            if (c != ALPHA_OPAQUE) {
                cubeOpaque[bone.cubeOfQuad[q]] = false;
            }
        }
        byte[] out = new byte[quads];
        for (int q = 0; q < quads; q++) {
            if (classes[q] == ALPHA_EMPTY) {
                out[q] = NONE;
                continue;
            }
            int cube = bone.cubeOfQuad[q];
            byte shape = bone.cubeShapes[cube];
            boolean cull = shape == BakedGeometry.SHAPE_NEGATIVE
                || shape == BakedGeometry.SHAPE_POSITIVE && cubeOpaque[cube];
            boolean translucent = classes[q] == ALPHA_TRANSLUCENT;
            out[q] = (byte) (translucent ? cull ? TRANSLUCENT_CULL : TRANSLUCENT : cull ? OPAQUE_CULL : OPAQUE);
        }
        return out;
    }
}

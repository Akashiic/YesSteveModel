package com.akashic.futura.armor;

/**
 * The quads of a cut of a biped armor box, laid out as {@code ModelBox} lays out its six faces on the 64x32 armor
 * texture (the same corners, texture rectangles, mirror and winding), but only from {@code from} to {@code to} along
 * the box's y: the sides take the matching rows of their texture, the top face only the top cut and the bottom face
 * only the bottom cut. No Minecraft classes.
 */
public final class ArmorBoxes {

    /** Floats per quad: four vertices of (x, y, z, u, v) in part pixels and texture fractions, then the normal. */
    public static final int QUAD_FLOATS = 4 * 5 + 3;
    public static final float TEXTURE_WIDTH = 64;
    public static final float TEXTURE_HEIGHT = 32;

    private ArmorBoxes() {}

    /** The quads of a piece's cut of its part, inflated by {@code inflate} pixels (plus the part's own extra). */
    public static float[] quads(ArmorFit.Piece piece, float inflate) {
        return quads(piece.part, inflate + piece.part.extraInflate, piece.from, piece.to);
    }

    /**
     * @param part    the biped part
     * @param inflate the box's growth on every side, in pixels ({@code ModelBox}'s last argument)
     * @param from    the cut's top along the part's y (pixels, the part's own coordinates)
     * @param to      the cut's bottom
     */
    public static float[] quads(ArmorFit.Part part, float inflate, float from, float to) {
        float y0 = part.y;
        int h = part.height;
        from = Math.max(from, y0);
        to = Math.min(to, y0 + h);
        boolean top = from <= y0;
        boolean bottom = to >= y0 + h;
        int w = part.width;
        int d = part.depth;
        // the box's corners, as ModelBox grows and mirrors them
        float x1 = part.x - inflate;
        float x2 = part.x + w + inflate;
        float z1 = part.z - inflate;
        float z2 = part.z + d + inflate;
        float grown = (h + 2 * inflate) / h;
        float ya = y0 - inflate + (from - y0) * grown;
        float yb = y0 - inflate + (to - y0) * grown;
        if (part.mirror) {
            float t = x1;
            x1 = x2;
            x2 = t;
        }
        // ModelBox's vertex names: v7 (x1 y1 z1), v (x2 y1 z1), v1 (x2 y2 z1), v2 (x1 y2 z1), and v3..v6 at z2
        float[] v7 = { x1, ya, z1 };
        float[] v0 = { x2, ya, z1 };
        float[] v1 = { x2, yb, z1 };
        float[] v2 = { x1, yb, z1 };
        float[] v3 = { x1, ya, z2 };
        float[] v4 = { x2, ya, z2 };
        float[] v5 = { x2, yb, z2 };
        float[] v6 = { x1, yb, z2 };
        int u = part.u;
        int v = part.v;
        // the sides' texture rows for this cut
        float va = v + d + (from - y0);
        float vb = v + d + (to - y0);
        int count = 4 + (top ? 1 : 0) + (bottom ? 1 : 0);
        float[] out = new float[count * QUAD_FLOATS];
        int q = 0;
        q = quad(out, q, part.mirror, v4, v0, v1, v5, u + d + w, va, u + d + w + d, vb);
        q = quad(out, q, part.mirror, v7, v3, v6, v2, u, va, u + d, vb);
        if (top) {
            q = quad(out, q, part.mirror, v4, v3, v7, v0, u + d, v, u + d + w, v + d);
        }
        if (bottom) {
            q = quad(out, q, part.mirror, v1, v2, v6, v5, u + d + w, v + d, u + d + w + w, v);
        }
        q = quad(out, q, part.mirror, v0, v7, v2, v1, u + d, va, u + d + w, vb);
        quad(out, q, part.mirror, v3, v4, v5, v6, u + d + w + d, va, u + d + w + d + w, vb);
        return out;
    }

    /**
     * One {@code TexturedQuad}: the corners get (u2, v1), (u1, v1), (u1, v2), (u2, v2); a mirrored box reverses the
     * corners ({@code flipFace}); the normal is {@code TexturedQuad.draw}'s, from the corners as drawn.
     */
    private static int quad(float[] out, int q, boolean flip, float[] a, float[] b, float[] c, float[] e, float u1,
        float v1, float u2, float v2) {
        float[][] corners = { a, b, c, e };
        float[][] uv = { { u2, v1 }, { u1, v1 }, { u1, v2 }, { u2, v2 } };
        int o = q * QUAD_FLOATS;
        for (int i = 0; i < 4; i++) {
            int k = flip ? 3 - i : i;
            out[o + i * 5] = corners[k][0];
            out[o + i * 5 + 1] = corners[k][1];
            out[o + i * 5 + 2] = corners[k][2];
            out[o + i * 5 + 3] = uv[k][0] / TEXTURE_WIDTH;
            out[o + i * 5 + 4] = uv[k][1] / TEXTURE_HEIGHT;
        }
        // (v1 - v0) and (v1 - v2), normal = second × first, as TexturedQuad.draw
        float ax = out[o + 5] - out[o];
        float ay = out[o + 6] - out[o + 1];
        float az = out[o + 7] - out[o + 2];
        float bx = out[o + 5] - out[o + 10];
        float by = out[o + 6] - out[o + 11];
        float bz = out[o + 7] - out[o + 12];
        float nx = by * az - bz * ay;
        float ny = bz * ax - bx * az;
        float nz = bx * ay - by * ax;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        out[o + 20] = nx;
        out[o + 21] = ny;
        out[o + 22] = nz;
        return q + 1;
    }
}

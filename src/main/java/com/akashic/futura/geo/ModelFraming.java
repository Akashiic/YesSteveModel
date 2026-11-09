/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/gui/GuiModelView.java and GuiModelFraming.java, Apache License 2.0.
 * Changes: float arrays and Mat4 instead of JOML; the per-bone bounds are computed here from the baked quads.
 */
package com.akashic.futura.geo;

/**
 * Fits a posed model into a screen rectangle, as upstream frames its GUI previews: the bounds of the visible bones in
 * view space are merged into an envelope kept across frames (so a looping or turning preview does not make the
 * camera pump), and the envelope's centre and a scale that fills 88% of the rectangle are returned. No Minecraft
 * classes.
 */
public final class ModelFraming {

    private final float[] boneBounds;
    private final float[] corner = new float[3];
    private final float[] m = new float[Mat4.FLOATS];
    private double minX, minY, minZ, maxX, maxY, maxZ;

    public ModelFraming(BakedGeometry geometry) {
        this.boneBounds = boneBounds(geometry);
        reset();
    }

    /** Per bone, the box {@code minX, minY, minZ, maxX, maxY, maxZ} of its own quads (min > max for none). */
    public static float[] boneBounds(BakedGeometry geometry) {
        float[] out = new float[geometry.bones.length * 6];
        for (int b = 0; b < geometry.bones.length; b++) {
            float[] q = geometry.bones[b].quads;
            int o = b * 6;
            out[o] = out[o + 1] = out[o + 2] = Float.POSITIVE_INFINITY;
            out[o + 3] = out[o + 4] = out[o + 5] = Float.NEGATIVE_INFINITY;
            for (int quad = 0; quad < q.length; quad += BakedGeometry.QUAD_FLOATS) {
                for (int v = 0; v < 4; v++) {
                    int k = quad + v * 5;
                    for (int a = 0; a < 3; a++) {
                        out[o + a] = Math.min(out[o + a], q[k + a]);
                        out[o + 3 + a] = Math.max(out[o + 3 + a], q[k + a]);
                    }
                }
            }
        }
        return out;
    }

    /** Forgets the envelope. */
    public void reset() {
        minX = minY = minZ = Double.POSITIVE_INFINITY;
        maxX = maxY = maxZ = Double.NEGATIVE_INFINITY;
    }

    /**
     * Adds the visible bones of a pose to the envelope.
     *
     * @param view model space to view space (x right, y up), column-major
     */
    public void include(PoseMatrices pose, float[] view) {
        for (int b = 0; b < pose.geometry().bones.length; b++) {
            int o = b * 6;
            if (!pose.visible(b) || boneBounds[o] > boneBounds[o + 3]) {
                continue;
            }
            Mat4.mul(view, 0, pose.bones, b * Mat4.FLOATS, m, 0);
            for (int c = 0; c < 8; c++) {
                float x = boneBounds[o + ((c & 1) == 0 ? 0 : 3)];
                float y = boneBounds[o + ((c & 2) == 0 ? 1 : 4)];
                float z = boneBounds[o + ((c & 4) == 0 ? 2 : 5)];
                Mat4.transformPoint(m, 0, x, y, z, corner);
                if (!Float.isFinite(corner[0]) || !Float.isFinite(corner[1]) || !Float.isFinite(corner[2])) {
                    continue;
                }
                minX = Math.min(minX, corner[0]);
                minY = Math.min(minY, corner[1]);
                minZ = Math.min(minZ, corner[2]);
                maxX = Math.max(maxX, corner[0]);
                maxY = Math.max(maxY, corner[1]);
                maxZ = Math.max(maxZ, corner[2]);
            }
        }
    }

    public boolean valid() {
        return minX <= maxX && minY <= maxY && minZ <= maxZ;
    }

    /**
     * @return {@code {scale, centreX, centreY, centreZ}}: draw at {@code S(scale) · T(-centre) · view}, the rectangle's
     *         centre at the origin; or null with an empty envelope
     */
    public float[] fit(float width, float height) {
        if (!valid() || !(width > 0) || !(height > 0)) {
            return null;
        }
        double scale = Math
            .min(width * 0.88 / Math.max(maxX - minX, 1.0e-6), height * 0.88 / Math.max(maxY - minY, 1.0e-6));
        scale = Math.min(scale, 1600 / Math.max(maxZ - minZ, 1.0e-6));
        // Degenerate geometry must not magnify without bound.
        scale = Math.min(scale, Math.max(width, height));
        return new float[] { (float) scale, (float) ((minX + maxX) / 2), (float) ((minY + maxY) / 2),
            (float) ((minZ + maxZ) / 2) };
    }
}

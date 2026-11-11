package com.akashic.futura.anim;

import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.geo.Mat4;
import com.akashic.futura.geo.PoseMatrices;

/**
 * Keeps the rod straight: after the pose, turns the {@code RightHandLocator} about the torso's vertical so the rod
 * points straight ahead of the torso (or straight behind it, when it leans back), whatever the model's arms do to the
 * hand. Both hands meet in front of the body, so each arm turns inward and its hand with it, and every model has its
 * own arms (bind angles, lengths): a pose written for one model would slant the rod to a side on another. Only the
 * slant is taken out; the rise or fall of the rod, and its roll, stay the clip's.
 * <p>
 * The rod's axis in the hand locator's frame: forward ({@code -z}) and {@link #AXIS_RISE_DEGREES} up, which is how the
 * vanilla rod lies in the hand, and how Kunftig places its 3D rods in a model's hand. No Minecraft classes.
 */
public final class RodAim {

    public static final String HAND = "RightHandLocator";
    public static final String BODY = "UpBody";
    /** The rod's rise above the hand locator's forward axis, in degrees. */
    public static final float AXIS_RISE_DEGREES = 10;
    /** The largest turn taken out at once, in degrees (a rod held across the body is meant so). */
    public static final float MAX_CORRECTION_DEGREES = 75;
    /** Below this horizontal part (the rod nearly vertical) the correction fades: its slant hardly shows. */
    static final float FADE_HORIZONTAL = 0.25f;

    private static final float AXIS_Y = (float) Math.sin(Math.toRadians(AXIS_RISE_DEGREES));
    private static final float AXIS_Z = (float) -Math.cos(Math.toRadians(AXIS_RISE_DEGREES));

    private final BakedGeometry geometry;
    private final PoseMatrices matrices;
    private final int hand;
    private final int parent;
    private final int body;
    private final float[] m = new float[Mat4.FLOATS];
    private final float[] rb = new float[9];
    private final float[] rh = new float[9];
    private final float[] rp = new float[9];
    private final float[] q = new float[9];
    private final float[] t = new float[9];

    public RodAim(BakedGeometry geometry) {
        this.geometry = geometry;
        this.matrices = new PoseMatrices(geometry);
        this.hand = geometry.indexOf(HAND);
        this.body = geometry.indexOf(BODY);
        this.parent = hand < 0 ? -1 : geometry.bones[hand].parent;
    }

    /** Whether the model has the bones it needs (the hand locator, its parent and the torso). */
    public boolean usable() {
        return hand >= 0 && parent >= 0 && body >= 0;
    }

    /**
     * Turns the hand locator of {@code pose} so the rod has no slant to either side of the torso.
     *
     * @param pose the frame's pose (rotation deltas in the baked convention), changed in place
     * @return the turn taken out, in radians (0 when none)
     */
    public float straighten(BonePose pose) {
        if (!usable() || pose == null) {
            return 0;
        }
        matrices.compute(pose);
        if (!matrices.visible(hand) || !matrices.visible(body)) {
            return 0;
        }
        rotation(body, rb);
        rotation(hand, rh);
        rotation(parent, rp);
        // the rod in the torso's frame
        float mx = rh[3] * AXIS_Y + rh[6] * AXIS_Z;
        float my = rh[4] * AXIS_Y + rh[7] * AXIS_Z;
        float mz = rh[5] * AXIS_Y + rh[8] * AXIS_Z;
        float x = rb[0] * mx + rb[1] * my + rb[2] * mz;
        float z = rb[6] * mx + rb[7] * my + rb[8] * mz;
        float horizontal = (float) Math.sqrt(x * x + z * z);
        if (horizontal < 1e-4f || Math.abs(z) < 1e-4f) {
            return 0;
        }
        // the smallest turn about the torso's vertical into its middle plane, toward the front or the back
        float a = (float) Math.atan(-x / z);
        float max = (float) Math.toRadians(MAX_CORRECTION_DEGREES);
        a = Math.max(-max, Math.min(max, a));
        if (horizontal < FADE_HORIZONTAL) {
            a *= horizontal / FADE_HORIZONTAL;
        }
        if (Math.abs(a) < 1e-4f) {
            return 0;
        }
        // Q = Rb · Ry(a) · Rbᵀ; the hand's new rotation is Q · Rh, its new local one Rpᵀ · Q · Rh
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);
        float[] ry = { c, 0, -s, 0, 1, 0, s, 0, c }; // column-major Ry(a)
        mul(rb, ry, t);
        mulTransposedRight(t, rb, q);
        mul(q, rh, t);
        mulTransposedLeft(rp, t, q);
        // q = Rz(fz) · Ry(fy) · Rx(fx); element (row r, column k) at q[k * 3 + r]
        float fy = (float) Math.asin(Math.max(-1, Math.min(1, -q[2])));
        float fx = (float) Math.atan2(q[5], q[8]);
        float fz = (float) Math.atan2(q[1], q[0]);
        float[] bind = geometry.bones[hand].rotation;
        int o = hand * BonePose.FLOATS;
        pose.values[o] = fx - bind[0];
        pose.values[o + 1] = fy - bind[1];
        pose.values[o + 2] = fz - bind[2];
        return a;
    }

    /** The bone's rotation (column-major 3×3, scale removed) in model space. */
    private void rotation(int bone, float[] out) {
        matrices.locator(bone, m, 0);
        for (int col = 0; col < 3; col++) {
            float a = m[col * 4];
            float b = m[col * 4 + 1];
            float d = m[col * 4 + 2];
            float len = (float) Math.sqrt(a * a + b * b + d * d);
            if (len < 1e-6f) {
                len = 1;
            }
            out[col * 3] = a / len;
            out[col * 3 + 1] = b / len;
            out[col * 3 + 2] = d / len;
        }
    }

    /** out = a · b (column-major 3×3). */
    private static void mul(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                out[col * 3 + row] = a[row] * b[col * 3] + a[3 + row] * b[col * 3 + 1] + a[6 + row] * b[col * 3 + 2];
            }
        }
    }

    /** out = a · bᵀ. */
    private static void mulTransposedRight(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                out[col * 3 + row] = a[row] * b[col] + a[3 + row] * b[3 + col] + a[6 + row] * b[6 + col];
            }
        }
    }

    /** out = aᵀ · b. */
    private static void mulTransposedLeft(float[] a, float[] b, float[] out) {
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                out[col * 3 + row] = a[row * 3] * b[col * 3] + a[row * 3 + 1] * b[col * 3 + 1]
                    + a[row * 3 + 2] * b[col * 3 + 2];
            }
        }
    }

    /** The rod's axis in the torso's frame for {@code pose} (x to the right, y up, z back), for tests and logs. */
    public float[] rodInBody(BonePose pose) {
        if (!usable()) {
            return null;
        }
        matrices.compute(pose);
        rotation(body, rb);
        rotation(hand, rh);
        float mx = rh[3] * AXIS_Y + rh[6] * AXIS_Z;
        float my = rh[4] * AXIS_Y + rh[7] * AXIS_Z;
        float mz = rh[5] * AXIS_Y + rh[8] * AXIS_Z;
        return new float[] { rb[0] * mx + rb[1] * my + rb[2] * mz, rb[3] * mx + rb[4] * my + rb[5] * mz,
            rb[6] * mx + rb[7] * my + rb[8] * mz };
    }
}

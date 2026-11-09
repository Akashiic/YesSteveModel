package com.akashic.futura.client.render;

/**
 * One frame's animation of every bone of a geometry, on top of the rest pose: rotation (radians, added), position
 * (blocks, added to the pivot) and scale (multiplied); a scale of 0 on all axes hides the bone and its children.
 */
public final class BonePose {

    public static final int FLOATS = 9;

    public final float[] values;

    public BonePose(int bones) {
        values = new float[bones * FLOATS];
        reset();
    }

    /** Back to the rest pose. */
    public void reset() {
        for (int o = 0; o < values.length; o += FLOATS) {
            for (int i = 0; i < 6; i++) {
                values[o + i] = 0;
            }
            values[o + 6] = 1;
            values[o + 7] = 1;
            values[o + 8] = 1;
        }
    }

    boolean hidden(int bone) {
        int o = bone * FLOATS;
        return values[o + 6] == 0 && values[o + 7] == 0 && values[o + 8] == 0;
    }
}

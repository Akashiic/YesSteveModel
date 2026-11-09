/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the colour, transparency and glow attributes of src/main/java/com/elfmcys/ysm/geckolib3/model/AnimatedGeoBone.java,
 * Apache License 2.0.
 * Changes: separate arrays instead of packed floats.
 */
package com.akashic.futura.anim;

import java.util.Arrays;

import com.akashic.futura.geo.BakedGeometry;

/**
 * Per-bone render attributes written by {@code ysm.bone_color}, {@code ysm.bone_transparency} and
 * {@code ysm.bone_glow}. They persist across frames until written again (upstream), are not inherited by children,
 * and are for the renderer to apply (not used by {@code GeometryRenderer} yet).
 */
public final class BoneAttributes {

    /** RGB per bone, 0xRRGGBB; default white. */
    public final int[] color;
    /** Alpha per bone, 0..255; default 255. */
    public final int[] alpha;
    /**
     * Glow per bone: -1 or 15 draw at full brightness (upstream stores -1 as 255, "keep the light"), 0..14 a block
     * light level; default 15 for {@code ysmGlow*} bones, else {@link #NO_GLOW}.
     */
    public final int[] glow;

    /** No glow written. */
    public static final int NO_GLOW = Integer.MIN_VALUE;

    private final BakedGeometry geometry;

    public BoneAttributes(BakedGeometry geometry) {
        this.geometry = geometry;
        int n = geometry.bones.length;
        color = new int[n];
        alpha = new int[n];
        glow = new int[n];
        reset();
    }

    public void reset() {
        Arrays.fill(color, 0xFFFFFF);
        Arrays.fill(alpha, 255);
        for (int i = 0; i < glow.length; i++) {
            glow[i] = geometry.bones[i].glow() ? 15 : NO_GLOW;
        }
    }

    void setColor(String bone, float r, float g, float b) {
        int i = geometry.indexOf(bone);
        if (i >= 0) {
            color[i] = channel(r) << 16 | channel(g) << 8 | channel(b);
        }
    }

    void setTransparency(String bone, float a) {
        int i = geometry.indexOf(bone);
        if (i >= 0) {
            alpha[i] = channel(a);
        }
    }

    void setGlow(String bone, float level) {
        int i = geometry.indexOf(bone);
        if (i >= 0) {
            glow[i] = Math.max(-1, Math.min(15, Math.round(level)));
        }
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}

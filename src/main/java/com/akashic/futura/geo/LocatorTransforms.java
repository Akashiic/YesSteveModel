package com.akashic.futura.geo;

/**
 * Where vanilla 1.7.10 attachments go relative to a locator frame. No Minecraft classes.
 *
 * <p>
 * Upstream draws a held item at {@code locator · T(0, -1/16, -0.1) · Rx(-90°)} and hands that frame to 1.20's
 * {@code ItemInHandRenderer} ({@code CustomPlayerItemInHandLayer}); vanilla 1.20 hands it
 * {@code arm · Rx(-90°) · Ry(180°) · T(1/16, 0.125, -0.625)} ({@code ItemInHandLayer}). 1.7.10 has no item display
 * contexts: {@code RenderPlayer.renderEquippedItems} goes to {@code arm · T(-1/16, 7/16, 1/16)} and applies its own
 * per-item transforms. Equating upstream's frame with 1.20's gives the 1.7.10 hand frame in locator space,
 * {@link #handToLegacyArm}; the arm frames of both versions are the same {@code ModelPart} transform. YSM model space
 * is the vanilla model space turned 180° about z (y up instead of down, x mirrored).
 */
public final class LocatorTransforms {

    private static final float PI = (float) Math.PI;

    private LocatorTransforms() {}

    /** {@code m = m · T(0, -1/16, -0.1) · Rx(-90°)}: upstream's item frame at a hand locator. */
    public static void upstreamItemFrame(float[] m, int o) {
        Mat4.translate(m, o, 0, -0.0625f, -0.1f);
        Mat4.rotateX(m, o, -PI / 2);
    }

    /** {@code m = m · Rx(-90°) · Ry(180°) · T(1/16, 0.125, -0.625)}: 1.20's item frame in right-arm space. */
    public static void vanillaItemFrame(float[] m, int o) {
        Mat4.rotateX(m, o, -PI / 2);
        Mat4.rotateY(m, o, PI);
        Mat4.translate(m, o, 0.0625f, 0.125f, -0.625f);
    }

    /** {@code m = m · T(-1/16, 7/16, 1/16)}: 1.7.10's hand frame in right-arm space, before its per-item transforms. */
    public static void legacyHandFrame(float[] m, int o) {
        Mat4.translate(m, o, -0.0625f, 0.4375f, 0.0625f);
    }

    /**
     * {@code m = m · T(0, 1/8, 7/80) · Rz(180°)}: from a hand locator to the frame where 1.7.10's
     * {@code renderEquippedItems} starts its per-item transforms. Equal to
     * {@code upstreamItemFrame · vanillaItemFrame⁻¹ · legacyHandFrame}.
     */
    public static void handToLegacyArm(float[] m, int o) {
        Mat4.translate(m, o, 0, 0.125f, 0.0875f);
        Mat4.rotateZ(m, o, PI);
    }

    /**
     * {@code m = m · Rz(180°) · T(0, 3/32, 0)}: from the {@code Head} locator (the head bone at its pivot, the neck) to
     * the frame where 1.7.10 draws a helmet-slot block or skull ({@code bipedHead.postRender}). Upstream draws head
     * items at {@code locator · S(0.625) · T(0, 0.25, 0)} ({@code CustomPlayerHeadLayer}), 1.20 at
     * {@code head · T(0, -0.25, 0) · Ry(180°) · S(0.625, -0.625, -0.625)} ({@code CustomHeadLayer}); equating them
     * puts the block 1.5 pixels lower than a plain flip would.
     */
    public static void headToLegacyHead(float[] m, int o) {
        Mat4.rotateZ(m, o, PI);
        Mat4.translate(m, o, 0, 0.09375f, 0);
    }
}

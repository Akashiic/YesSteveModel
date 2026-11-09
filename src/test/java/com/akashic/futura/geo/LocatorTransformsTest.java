package com.akashic.futura.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class LocatorTransformsTest {

    private static final float PI = (float) Math.PI;

    @Test
    void legacyHandFrameIsUpstreamFrameMappedThroughVanilla() {
        float[] upstream = Mat4.identity();
        LocatorTransforms.upstreamItemFrame(upstream, 0);
        float[] vanilla = Mat4.identity();
        LocatorTransforms.vanillaItemFrame(vanilla, 0);
        float[] legacy = Mat4.identity();
        LocatorTransforms.legacyHandFrame(legacy, 0);
        float[] expected = Mat4Test.mul(Mat4Test.mul(upstream, Mat4Test.inverse(vanilla)), legacy);

        float[] actual = Mat4.identity();
        LocatorTransforms.handToLegacyArm(actual, 0);
        assertArrayEquals(expected, actual, 1e-5f);
    }

    @Test
    void upstreamItemFrameHasTheOrientationOfVanillasInModelSpace() {
        // YSM model space is vanilla model space turned 180° about z: locator · Rx(-90°) must equal
        // locator · Rz(180°) · Rx(-90°) · Ry(180°) in rotation.
        float[] upstream = Mat4.identity();
        Mat4.rotateX(upstream, 0, -PI / 2);
        float[] vanilla = Mat4.identity();
        Mat4.rotateZ(vanilla, 0, PI);
        Mat4.rotateX(vanilla, 0, -PI / 2);
        Mat4.rotateY(vanilla, 0, PI);
        assertArrayEquals(upstream, vanilla, 1e-5f);
    }

    @Test
    void legacyHeadFrameMatchesUpstreamHeadItems() {
        // Upstream: locator · S(0.625) · T(0, 0.25, 0); 1.20: head · T(0, -0.25, 0) · Ry(180°) · S(0.625, -0.625,
        // -0.625). With head = locator · headToLegacyHead both give the same matrix.
        float[] upstream = Mat4.identity();
        Mat4.scale(upstream, 0, 0.625f, 0.625f, 0.625f);
        Mat4.translate(upstream, 0, 0, 0.25f, 0);
        float[] vanilla = Mat4.identity();
        LocatorTransforms.headToLegacyHead(vanilla, 0);
        Mat4.translate(vanilla, 0, 0, -0.25f, 0);
        Mat4.rotateY(vanilla, 0, PI);
        Mat4.scale(vanilla, 0, 0.625f, -0.625f, -0.625f);
        assertArrayEquals(upstream, vanilla, 1e-5f);
    }

    @Test
    void castRodTipIsTheStickEndAheadOfTheHand() {
        // Worked out by hand from RenderPlayer.renderEquippedItems and ItemRenderer.renderItem (1.7.10).
        assertArrayEquals(new float[] { -0.0040f, 0.1750f, -0.7649f }, LocatorTransforms.castRodTip(), 1e-3f);
    }

    @Test
    void castRodTipIsACopy() {
        float[] a = LocatorTransforms.castRodTip();
        a[0] = 99;
        assertArrayEquals(new float[] { -0.0040f, 0.1750f, -0.7649f }, LocatorTransforms.castRodTip(), 1e-3f);
    }

    @Test
    void heldItemsCrossTheHandLocator() {
        // The stick's bottom end sits 0.19 block behind the hand; the stick passes about 2 cm from the locator.
        float[] m = Mat4.identity();
        LocatorTransforms.handToLegacyArm(m, 0);
        LocatorTransforms.legacyFull3DItem(m, 0, false);
        LocatorTransforms.legacyItemIn2D(m, 0);
        float[] grip = new float[3];
        Mat4.transformPoint(m, 0, 0.875f, 0.125f, -0.03125f, grip);
        assertArrayEquals(new float[] { 0.0267f, -0.0131f, 0.1901f }, grip, 1e-3f);
        // and the rod, turned half a turn by shouldRotateAroundWhenRendering, has its tip there too
        float[] rod = Mat4.identity();
        LocatorTransforms.handToLegacyArm(rod, 0);
        LocatorTransforms.legacyFull3DItem(rod, 0, true);
        LocatorTransforms.legacyItemIn2D(rod, 0);
        float[] tip = new float[3];
        Mat4.transformPoint(rod, 0, 0.125f, 0.9375f, -0.03125f, tip);
        assertArrayEquals(new float[] { 0.0008f, 0.0003f, -0.8369f }, tip, 1e-3f);
    }
}

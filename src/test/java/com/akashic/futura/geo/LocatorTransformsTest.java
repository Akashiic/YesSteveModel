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
}

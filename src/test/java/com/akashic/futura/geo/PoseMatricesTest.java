package com.akashic.futura.geo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.akashic.futura.anim.BonePose;
import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.model.GeoModel;

class PoseMatricesTest {

    private static final float EPS = 1e-5f;

    /** arm (pivot 5, 22, 0, rest rotation z 90°) > hand > RightHandLocator, RightHandLocator2; leg. */
    private static BakedGeometry geometry() throws IOException {
        return GeoBaker.bake(
            FormatGson.create()
                .fromJson(
                    "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"bones\":["
                        + "{\"name\":\"arm\",\"pivot\":[5,22,0],\"rotation\":[0,0,90]},"
                        + "{\"name\":\"hand\",\"parent\":\"arm\",\"pivot\":[5,12,0]},"
                        + "{\"name\":\"RightHandLocator\",\"parent\":\"hand\",\"pivot\":[5,12,0]},"
                        + "{\"name\":\"RightHandLocator2\",\"parent\":\"arm\",\"pivot\":[6,12,0]},"
                        + "{\"name\":\"leg\",\"pivot\":[2,12,0]}]}]}",
                    GeoModel.class));
    }

    @Test
    void restPoseLocatorIsTheBonePivotAfterItsParentsRotation() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        int locator = g.indexOf("RightHandLocator");
        float[] m = new float[16];
        pose.locator(locator, m, 0);
        // The arm turns 90° about z around its pivot (-5, 22) pixels (x negated): the hand pivot, 10 pixels below it,
        // (0, -10) from the pivot, turns to (10, 0).
        float[] origin = Mat4Test.point(m, 0, 0, 0);
        assertArrayEquals(new float[] { 5 / 16f, 22 / 16f, 0 }, origin, EPS);
        // The locator's y axis follows the arm: +90° about z sends y to -x.
        float[] y = Mat4Test.point(m, 0, 1, 0);
        assertArrayEquals(new float[] { 5 / 16f - 1, 22 / 16f, 0 }, y, EPS);
    }

    @Test
    void animationMovesTheLocatorAndZeroScaleHidesTheSubtree() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        BonePose anim = new BonePose(g.bones.length);
        int hand = g.indexOf("hand");
        // Move the hand by one block on z.
        anim.values[hand * BonePose.FLOATS + 5] = 1;
        pose.compute(anim);
        float[] m = new float[16];
        pose.locator(g.indexOf("RightHandLocator"), m, 0);
        assertEquals(1, Mat4Test.point(m, 0, 0, 0)[2], EPS);

        assertArrayEquals(
            new int[] { g.indexOf("RightHandLocator"), g.indexOf("RightHandLocator2") },
            pose.visibleGroup("RightHandLocator"));
        int arm = g.indexOf("arm");
        anim.values[arm * BonePose.FLOATS + 6] = 0;
        anim.values[arm * BonePose.FLOATS + 7] = 0;
        anim.values[arm * BonePose.FLOATS + 8] = 0;
        pose.compute(anim);
        assertFalse(pose.visible(arm));
        assertFalse(pose.visible(hand));
        assertTrue(pose.visible(g.indexOf("leg")));
        assertEquals(0, pose.visibleGroup("RightHandLocator").length);
        assertEquals(2, pose.group("RightHandLocator").length);
    }

    @Test
    void nonFiniteValuesHideTheBone() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        BonePose anim = new BonePose(g.bones.length);
        anim.values[g.indexOf("leg") * BonePose.FLOATS] = Float.NaN;
        pose.compute(anim);
        assertFalse(pose.visible(g.indexOf("leg")));
        assertTrue(pose.visible(g.indexOf("arm")));
    }

    @Test
    void boneMatrixMatchesTheNestedGlTransforms() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        BonePose anim = new BonePose(g.bones.length);
        int hand = g.indexOf("hand");
        int o = hand * BonePose.FLOATS;
        anim.values[o] = 0.3f;
        anim.values[o + 1] = -0.2f;
        anim.values[o + 2] = 0.1f;
        anim.values[o + 6] = 2;
        pose.compute(anim);
        BakedGeometry.Bone a = g.bones[g.indexOf("arm")];
        BakedGeometry.Bone h = g.bones[hand];
        float[] expected = Mat4.identity();
        Mat4.translate(expected, 0, a.pivot[0], a.pivot[1], a.pivot[2]);
        Mat4.rotateZ(expected, 0, a.rotation[2]);
        Mat4.translate(expected, 0, -a.pivot[0], -a.pivot[1], -a.pivot[2]);
        Mat4.translate(expected, 0, h.pivot[0], h.pivot[1], h.pivot[2]);
        Mat4.rotateZ(expected, 0, 0.1f);
        Mat4.rotateY(expected, 0, -0.2f);
        Mat4.rotateX(expected, 0, 0.3f);
        Mat4.scale(expected, 0, 2, 1, 1);
        Mat4.translate(expected, 0, -h.pivot[0], -h.pivot[1], -h.pivot[2]);
        float[] actual = new float[16];
        Mat4.copy(pose.bones, hand * 16, actual, 0);
        assertArrayEquals(expected, actual, EPS);
    }

    @Test
    void groupNamesDropTrailingDigits() {
        assertEquals("RightHandLocator", PoseMatrices.groupName("RightHandLocator12"));
        assertEquals("Head", PoseMatrices.groupName("Head"));
        assertEquals("123", PoseMatrices.groupName("123"));
    }

    @Test
    void subtreeIgnoresTheAncestorsAndHidesEverythingElse() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        int hand = g.indexOf("hand");
        pose.computeSubtree(hand, null);
        assertTrue(pose.visible(hand));
        assertTrue(pose.visible(g.indexOf("RightHandLocator")));
        assertFalse(pose.visible(g.indexOf("arm")));
        assertFalse(pose.visible(g.indexOf("RightHandLocator2")));
        assertFalse(pose.visible(g.indexOf("leg")));
        // Without the arm's 90° turn, the locator sits at its own pivot (x negated).
        float[] m = new float[16];
        pose.locator(g.indexOf("RightHandLocator"), m, 0);
        assertArrayEquals(new float[] { -5 / 16f, 12 / 16f, 0 }, Mat4Test.point(m, 0, 0, 0), EPS);
        // A full compute brings everything back.
        pose.compute(null);
        assertTrue(pose.visible(g.indexOf("leg")));
    }
}

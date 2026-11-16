package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

/** Bone poses another mod gives a player (a hand sign): blended over any bones by name, for a moment. */
class BonePosesTest {

    private static final UUID PLAYER = new UUID(6, 6);

    @Test
    void aPoseIsBlendedOverTheNamedBonesForAMoment() throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/default/"));
        BakedGeometry g = model.main;
        BonePose pose = new BonePose(g.bones.length);
        int arm = g.indexOf("RightArm") * BonePose.FLOATS;
        int head = g.indexOf("Head") * BonePose.FLOATS;
        BonePoses poses = new BonePoses();
        assertFalse(poses.apply(PLAYER, pose, g, 0), "nothing set");
        String[] bones = { "RightArm", "NoSuchFingerBone", "Head" };
        float[] degrees = { 90, 30, -20, 1, 2, 3, 10, 0, 0 };
        assertTrue(poses.set(PLAYER, bones, degrees, 1, 0));
        assertTrue(poses.apply(PLAYER, pose, g, BonePoses.VALID_NANOS / 2));
        // as an animation file's rotation: radians, x and y negated, z kept
        assertEquals(-Math.toRadians(90), pose.values[arm], 1e-5);
        assertEquals(-Math.toRadians(30), pose.values[arm + 1], 1e-5);
        assertEquals(Math.toRadians(-20), pose.values[arm + 2], 1e-5);
        assertEquals(-Math.toRadians(10), pose.values[head], 1e-5);
        // half weight: half way from the animation's rotation
        pose.reset();
        pose.values[arm] = 1;
        assertTrue(poses.set(PLAYER, bones, degrees, 0.5f, 0));
        assertTrue(poses.apply(PLAYER, pose, g, 1));
        assertEquals((1 - Math.toRadians(90)) / 2, pose.values[arm], 1e-5);
        // not set again: it lapses
        pose.reset();
        assertFalse(poses.apply(PLAYER, pose, g, BonePoses.VALID_NANOS * 2));
        assertEquals(0f, pose.values[arm], 1e-6f);
    }

    @Test
    void badPosesAreRefusedAndEmptyClears() throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/default/"));
        BonePose pose = new BonePose(model.main.bones.length);
        BonePoses poses = new BonePoses();
        assertFalse(poses.set(PLAYER, new String[] { "Head" }, new float[] { 0, 0 }, 1, 0), "two numbers");
        assertFalse(poses.set(PLAYER, new String[] { "Head" }, new float[] { 0, Float.NaN, 0 }, 1, 0));
        assertFalse(poses.set(PLAYER, new String[] { null }, new float[] { 0, 0, 0 }, 1, 0));
        assertFalse(
            poses.set(PLAYER, new String[BonePoses.MAX_BONES + 1], new float[(BonePoses.MAX_BONES + 1) * 3], 1, 0));
        assertFalse(poses.apply(PLAYER, pose, model.main, 0));
        assertTrue(poses.set(PLAYER, new String[] { "Head" }, new float[] { 5, 0, 0 }, 1, 0));
        assertTrue(poses.set(PLAYER, null, null, 1, 0), "null clears");
        assertFalse(poses.apply(PLAYER, pose, model.main, 0));
        // a model without any of the bones: nothing to do
        assertTrue(poses.set(PLAYER, new String[] { "NoSuchBone" }, new float[] { 5, 0, 0 }, 1, 0));
        assertFalse(poses.apply(PLAYER, pose, model.main, 0));
    }
}

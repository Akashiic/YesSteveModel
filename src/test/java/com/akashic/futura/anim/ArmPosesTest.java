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

/** Arm poses another mod gives a player (Kunftig's held fish): written over the arms, for a moment. */
class ArmPosesTest {

    private static final UUID PLAYER = new UUID(5, 5);

    @Test
    void aPoseReplacesTheArmsForAMoment() throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/default/"));
        BakedGeometry g = model.main;
        BonePose pose = new BonePose(g.bones.length);
        int right = g.indexOf(ArmPoses.RIGHT_ARM);
        int left = g.indexOf(ArmPoses.LEFT_ARM);
        ArmPoses poses = new ArmPoses();
        assertFalse(poses.apply(PLAYER, pose, g, 0), "nothing set");
        assertTrue(poses.set(PLAYER, new float[] { -1.5f, 0.1f, 0.2f }, null, 0));
        assertTrue(poses.apply(PLAYER, pose, g, ArmPoses.VALID_NANOS / 2));
        // the biped's frame is ours under scale(-1, -1, 1): x and y turn the other way, z the same
        assertEquals(1.5f, pose.values[right * BonePose.FLOATS], 1e-6f);
        assertEquals(-0.1f, pose.values[right * BonePose.FLOATS + 1], 1e-6f);
        assertEquals(0.2f, pose.values[right * BonePose.FLOATS + 2], 1e-6f);
        assertEquals(0f, pose.values[left * BonePose.FLOATS], 1e-6f, "the left arm is the animation's");
        // not set again: it lapses
        pose.reset();
        assertFalse(poses.apply(PLAYER, pose, g, ArmPoses.VALID_NANOS * 2));
        assertEquals(0f, pose.values[right * BonePose.FLOATS], 1e-6f);
        // bad poses are refused; both null clears
        assertFalse(poses.set(PLAYER, new float[] { Float.NaN, 0, 0 }, null, 0));
        assertFalse(poses.set(PLAYER, new float[] { 0, 0 }, null, 0));
        assertTrue(poses.set(PLAYER, null, new float[] { 0, 0, -0.5f }, 0));
        assertTrue(poses.apply(PLAYER, pose, g, 1));
        assertEquals(-0.5f, pose.values[left * BonePose.FLOATS + 2], 1e-6f);
        assertTrue(poses.set(PLAYER, null, null, 0));
        assertFalse(poses.apply(PLAYER, pose, g, 1));
    }
}

package com.akashic.futura.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.akashic.futura.anim.BonePose;
import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.model.GeoModel;

class ModelFramingTest {

    private static final float EPS = 1e-4f;

    /** body: a 16x32x8 pixel cube on the ground; prop: a 64-pixel cube high above, as a hidden prop would be. */
    private static BakedGeometry geometry() throws IOException {
        return GeoBaker.bake(
            FormatGson.create()
                .fromJson(
                    "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
                        + "\"description\":{\"texture_width\":64,\"texture_height\":64},\"bones\":["
                        + "{\"name\":\"body\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-8,0,-4],\"size\":[16,32,8],"
                        + "\"uv\":[0,0]}]},"
                        + "{\"name\":\"prop\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-32,64,-32],"
                        + "\"size\":[64,64,64],\"uv\":[0,0]}]},"
                        + "{\"name\":\"empty\",\"pivot\":[0,0,0]}]}]}",
                    GeoModel.class));
    }

    @Test
    void boneBoundsAreTheBoxOfTheBoneQuads() throws IOException {
        BakedGeometry g = geometry();
        float[] b = ModelFraming.boneBounds(g);
        int body = g.indexOf("body") * 6;
        assertEquals(-0.5f, b[body], EPS);
        assertEquals(0, b[body + 1], EPS);
        assertEquals(0.5f, b[body + 3], EPS);
        assertEquals(2, b[body + 4], EPS);
        int empty = g.indexOf("empty") * 6;
        assertTrue(b[empty] > b[empty + 3], "a bone without quads has no box");
    }

    @Test
    void fitsTheVisibleBonesInto88PercentOfTheRectangle() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        BonePose anim = new BonePose(g.bones.length);
        int prop = g.indexOf("prop") * BonePose.FLOATS;
        anim.values[prop + 6] = anim.values[prop + 7] = anim.values[prop + 8] = 0;
        pose.compute(anim);
        ModelFraming framing = new ModelFraming(g);
        assertNull(framing.fit(100, 100), "nothing included yet");
        framing.include(pose, Mat4.identity());
        float[] camera = framing.fit(100, 100);
        assertNotNull(camera);
        // The body alone: 1 x 2 blocks; the height limits: 88 / 2.
        assertEquals(44, camera[0], EPS);
        assertEquals(0, camera[1], EPS);
        assertEquals(1, camera[2], EPS);
    }

    @Test
    void theEnvelopeKeepsEverythingSeenSoFar() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        ModelFraming framing = new ModelFraming(g);
        pose.compute(null);
        framing.include(pose, Mat4.identity());
        float withProp = framing.fit(100, 100)[0];
        BonePose anim = new BonePose(g.bones.length);
        int prop = g.indexOf("prop") * BonePose.FLOATS;
        anim.values[prop + 6] = anim.values[prop + 7] = anim.values[prop + 8] = 0;
        pose.compute(anim);
        framing.include(pose, Mat4.identity());
        assertEquals(withProp, framing.fit(100, 100)[0], EPS, "the camera does not pump");
        framing.reset();
        assertFalse(framing.valid());
        framing.include(pose, Mat4.identity());
        assertTrue(framing.fit(100, 100)[0] > withProp);
    }

    @Test
    void theViewTurnsTheBounds() throws IOException {
        BakedGeometry g = geometry();
        PoseMatrices pose = new PoseMatrices(g);
        BonePose anim = new BonePose(g.bones.length);
        int prop = g.indexOf("prop") * BonePose.FLOATS;
        anim.values[prop + 6] = anim.values[prop + 7] = anim.values[prop + 8] = 0;
        pose.compute(anim);
        ModelFraming framing = new ModelFraming(g);
        float[] view = Mat4.identity();
        // Lying on its side: 2 blocks wide, 1 high; the width limits: 88 / 2.
        Mat4.rotateZ(view, 0, (float) Math.toRadians(90));
        framing.include(pose, view);
        float[] camera = framing.fit(100, 1000);
        assertEquals(44, camera[0], EPS);
        assertEquals(-1, camera[1], EPS);
    }
}

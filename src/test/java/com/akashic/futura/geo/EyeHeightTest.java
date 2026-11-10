package com.akashic.futura.geo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.akashic.futura.anim.BonePose;
import com.akashic.futura.anim.ModelAnimations;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.model.GeoModel;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.molang.MolangValue;

/** The first-person camera height from a model's ViewLocator or Head, its bounds and its smoothing. */
class EyeHeightTest {

    private static final float EPS = 1e-4f;

    /** body > Head (pivot 24 px), plus an optional ViewLocator2 under the head. */
    private static BakedGeometry geometry(boolean viewLocator) throws IOException {
        String bones = "{\"name\":\"body\",\"pivot\":[0,12,0]},"
            + "{\"name\":\"Head\",\"parent\":\"body\",\"pivot\":[0,24,0]}"
            + (viewLocator ? ",{\"name\":\"ViewLocator2\",\"parent\":\"Head\",\"pivot\":[0,28,-2]}" : "");
        return GeoBaker.bake(
            FormatGson.create()
                .fromJson(
                    "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"bones\":[" + bones + "]}]}",
                    GeoModel.class));
    }

    private static float[] placement(float feetY, float scale) {
        float[] m = Mat4.identity();
        Mat4.translate(m, 0, 0, feetY, 0);
        Mat4.rotateY(m, 0, 1.2f);
        Mat4.scale(m, 0, scale, scale, scale);
        return m;
    }

    @Test
    void aHeadAt24PixelsKeepsTheVanillaCamera() throws IOException {
        BakedGeometry g = geometry(false);
        PoseMatrices pose = new PoseMatrices(g);
        int bone = EyeHeight.viewBone(pose);
        assertEquals(g.indexOf("Head"), bone);
        float y = EyeHeight.pointY(placement(0, 1), pose, bone, new float[16]);
        assertEquals(1.5f, y, EPS);
        assertEquals(0, EyeHeight.offset(y, EyeHeight.VANILLA_EYE, 0), EPS);
        // The local player's space: the feet 1.62 under the camera entity, the camera at 0.
        float local = EyeHeight.pointY(placement(-1.62f, 1), pose, bone, new float[16]);
        assertEquals(0, EyeHeight.offset(local, 0, -1.62f), EPS);
    }

    @Test
    void theViewLocatorComesBeforeTheHead() throws IOException {
        BakedGeometry g = geometry(true);
        PoseMatrices pose = new PoseMatrices(g);
        int bone = EyeHeight.viewBone(pose);
        assertEquals(g.indexOf("ViewLocator2"), bone);
        float y = EyeHeight.pointY(placement(0, 1), pose, bone, new float[16]);
        assertEquals(28 / 16f, y, EPS);
        assertEquals(28 / 16f + EyeHeight.EYE_ABOVE_POINT - EyeHeight.VANILLA_EYE, EyeHeight.offset(y, 1.62f, 0), EPS);
    }

    @Test
    void theScaleAndTheAnimatedParentsMoveIt() throws IOException {
        BakedGeometry g = geometry(false);
        PoseMatrices pose = new PoseMatrices(g);
        int head = g.indexOf("Head");
        float half = EyeHeight.pointY(placement(0, 0.5f), pose, head, new float[16]);
        assertEquals(0.75f, half, EPS);
        assertEquals(0.75f + 0.12f - 1.62f, EyeHeight.offset(half, 1.62f, 0), EPS);
        // The body crouches 0.3 blocks and leans: the head's pivot follows; the head's own turn does not move it.
        BonePose anim = new BonePose(g.bones.length);
        int body = g.indexOf("body");
        anim.values[body * BonePose.FLOATS + 4] = -0.3f;
        anim.values[head * BonePose.FLOATS] = 1.0f;
        anim.values[head * BonePose.FLOATS + 1] = 0.7f;
        pose.compute(anim);
        float crouched = EyeHeight.pointY(placement(0, 1), pose, head, new float[16]);
        assertEquals(1.2f, crouched, EPS);
        anim.values[body * BonePose.FLOATS] = 0.5f;
        pose.compute(anim);
        float leaning = EyeHeight.pointY(placement(0, 1), pose, head, new float[16]);
        assertTrue(leaning < crouched, "leaning forward lowers the head: " + leaning);
    }

    @Test
    void boundsKeepTheCameraSane() {
        // A tiny model: not below 0.2 over the feet.
        assertEquals(0.2f - 1.62f, EyeHeight.offset(0.01f, 1.62f, 0), EPS);
        // A giant: at most MAX_OFFSET up.
        assertEquals(EyeHeight.MAX_OFFSET, EyeHeight.offset(10, 1.62f, 0), EPS);
        assertEquals(EyeHeight.MIN_OFFSET, EyeHeight.offset(-10, 1.62f, -10), EPS);
        assertEquals(0, EyeHeight.offset(Float.NaN, 1.62f, 0), EPS);
        assertEquals(0, EyeHeight.offset(1, Float.POSITIVE_INFINITY, 0), EPS);
        // A ceiling: the camera stops CLEARANCE under it; never lower than vanilla for that.
        assertEquals(0.4f, EyeHeight.limitUp(1, 0.5f), EPS);
        assertEquals(0, EyeHeight.limitUp(1, 0.05f), EPS);
        assertEquals(1, EyeHeight.limitUp(1, Float.MAX_VALUE), EPS);
        assertEquals(-0.5f, EyeHeight.limitUp(-0.5f, 0), EPS);
    }

    @Test
    void smoothingIsFrameRateIndependentAndSettles() {
        EyeHeight.Smoother a = new EyeHeight.Smoother();
        EyeHeight.Smoother b = new EyeHeight.Smoother();
        for (int i = 0; i < 6; i++) {
            a.update(-0.5f, 1 / 60f);
        }
        for (int i = 0; i < 3; i++) {
            b.update(-0.5f, 1 / 30f);
        }
        assertEquals(a.value(), b.value(), 1e-5f);
        assertTrue(a.value() < 0 && a.value() > -0.5f, "on its way: " + a.value());
        for (int i = 0; i < 120; i++) {
            a.update(-0.5f, 1 / 60f);
        }
        assertEquals(-0.5f, a.value(), 0);
        // A second call in the same frame and a NaN target change nothing; a long pause snaps.
        float before = b.value();
        b.update(1, 0);
        b.update(Float.NaN, 1 / 60f);
        assertEquals(before, b.value(), 0);
        b.update(0.3f, 2);
        assertEquals(0.3f, b.value(), 0);
        b.reset();
        assertEquals(0, b.value(), 0);
    }

    /** The view offset of a built-in model after some frames of a state, with the model's scale. */
    private static float builtinOffset(String folder, boolean sneaking) throws IOException {
        return builtinOffset(folder, sneaking, Float.NaN);
    }

    private static float builtinOffset(String folder, boolean sneaking, float playerSize) throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + folder + "/"));
        PlayerAnimator animator = new PlayerAnimator(ModelAnimations.compile(model));
        if (!Float.isNaN(playerSize)) {
            animator.variables()
                .set("player_size", MolangValue.of((double) playerSize));
        }
        PlayerSnapshot s = new PlayerSnapshot();
        s.sneaking = sneaking;
        for (int frame = 0; frame < 60; frame++) {
            s.ticksExisted = frame / 3;
            s.partialTicks = (frame % 3) / 3f;
            animator.update(s, s.ticksExisted + s.partialTicks);
        }
        PoseMatrices pose = new PoseMatrices(model.main);
        pose.compute(animator.pose());
        float[] m = Mat4.identity();
        Mat4.scale(m, 0, model.widthScale, model.heightScale, model.widthScale);
        float y = EyeHeight.pointY(m, pose, EyeHeight.viewBone(pose), new float[16]);
        return EyeHeight.offset(y, EyeHeight.VANILLA_EYE, 0);
    }

    @Test
    void builtinModelsStandingAndSneaking() throws IOException {
        float alex = builtinOffset("misc/1_alex", false);
        assertEquals(0, alex, 0.08f, "Alex's head sits at 24 px: about vanilla's camera");
        float standing = builtinOffset("default", false);
        assertEquals(33.05f * 0.7f / 16 + 0.12f - 1.62f, standing, 0.08f);
        float sneaking = builtinOffset("default", true);
        assertTrue(sneaking < standing - 0.05f, "sneaking lowers the camera: " + standing + " -> " + sneaking);
    }

    @Test
    void momosPlayerSizeScalesTheCamera() throws IOException {
        float normal = builtinOffset("wine_fox/14_momo", false);
        float small = builtinOffset("wine_fox/14_momo", false, 0.4f);
        float big = builtinOffset("wine_fox/14_momo", false, 2f);
        // v.player_size scales MRoot: the head's height over the feet scales with it.
        assertEquals(0.4f * (normal + 1.5f) + 0.12f - 1.62f, small, 0.05f);
        assertEquals(2f * (normal + 1.5f) + 0.12f - 1.62f, big, 0.05f);
    }
}

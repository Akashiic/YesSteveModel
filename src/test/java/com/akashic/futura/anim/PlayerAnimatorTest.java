package com.akashic.futura.anim;

import static com.akashic.futura.anim.AnimTestSupport.DEG;
import static com.akashic.futura.anim.AnimTestSupport.EPS;
import static com.akashic.futura.anim.AnimTestSupport.geometry;
import static com.akashic.futura.anim.AnimTestSupport.library;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.molang.MolangValue;

/** The player stack end to end: state selection, items, fallback, extras, head look, pose units. */
class PlayerAnimatorTest {

    private static final BakedGeometry GEO = geometry("Root", "Body:Root", "Head:Body", "RightArm:Body", "Hat:Head");

    /** Each main state moves Root on x by a distinct number of pixels. */
    private static final String STATES = "\"idle\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[1,0,0]}}},"
        + "\"walk\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[2,0,0]}}},"
        + "\"run\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[3,0,0]}}},"
        + "\"sit\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[4,0,0]}}},"
        + "\"ride_pig\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[5,0,0]}}},"
        + "\"vehicle$minecraft:horse\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[6,0,0]}}},"
        + "\"hold_mainhand:sword\":{\"loop\":true,\"bones\":{\"RightArm\":{\"rotation\":[30,0,0]}}},"
        + "\"hold_mainhand:empty\":{\"loop\":true,\"bones\":{\"RightArm\":{\"rotation\":[10,0,0]}}},"
        + "\"swing_hand\":{\"animation_length\":0.3,\"bones\":{\"RightArm\":{\"rotation\":[90,0,0]}}},"
        + "\"extra0\":{\"animation_length\":0.5,\"bones\":{\"Body\":{\"position\":[0,7,0]}}},"
        + "\"parallel0\":{\"loop\":true,\"bones\":{\"RightArm\":{\"rotation\":[0,0,5]}}},"
        + "\"pre_parallel0\":{\"loop\":true,\"bones\":{\"Hat\":{\"scale\":0}}},"
        + "\"head$minecraft:diamond_helmet\":{\"loop\":true,\"bones\":{\"Hat\":{\"scale\":[2,2,2]}}}";

    private static PlayerAnimator animator(String animations, AnimationLibrary fallback, String originVer) {
        return new PlayerAnimator(ModelAnimations.of(GEO, library(animations, fallback), originVer));
    }

    /** Runs whole ticks from {@code from} to {@code to}, inclusive. */
    private static void run(PlayerAnimator a, PlayerSnapshot s, int from, int to) {
        for (int t = from; t <= to; t++) {
            s.ticksExisted = t;
            a.update(s, t);
        }
    }

    private static float x(PlayerAnimator a, String bone) {
        return a.pose().values[GEO.indexOf(bone) * BonePose.FLOATS + 3];
    }

    private static float value(PlayerAnimator a, String bone, int index) {
        return a.pose().values[GEO.indexOf(bone) * BonePose.FLOATS + index];
    }

    @Test
    void mainControllerFollowsTheInputs() {
        PlayerAnimator a = animator(STATES, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 5);
        // position: pixels to blocks, x negated
        assertEquals(-1 / 16f, x(a, "Root"), EPS);
        s.limbSwingAmount = 0.8f;
        run(a, s, 6, 10);
        assertEquals(-2 / 16f, x(a, "Root"), EPS);
        s.sprinting = true;
        run(a, s, 11, 15);
        assertEquals(-3 / 16f, x(a, "Root"), EPS);
        assertEquals("run", a.selectedControl());
        assertEquals(
            MolangValue.ONE,
            a.context()
                .call("ctrl", "run", new MolangValue[0]));
        assertEquals(
            MolangValue.ZERO,
            a.context()
                .call("ctrl", "walk", new MolangValue[0]));
    }

    @Test
    void vehiclesReplaceTheMainState() {
        PlayerAnimator a = animator(STATES, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        s.limbSwingAmount = 0.8f;
        s.riding = true;
        s.vehicleId = "minecraft:minecart";
        s.vehicleKind = PlayerInputs.VehicleKind.OTHER;
        run(a, s, 0, 5);
        assertEquals(-4 / 16f, x(a, "Root"), EPS);
        s.vehicleId = "minecraft:pig";
        s.vehicleKind = PlayerInputs.VehicleKind.PIG;
        run(a, s, 6, 10);
        assertEquals(-5 / 16f, x(a, "Root"), EPS);
        // vehicle$<id> wins over the legacy kinds
        s.vehicleId = "minecraft:horse";
        s.vehicleKind = PlayerInputs.VehicleKind.SADDLEABLE;
        run(a, s, 11, 15);
        assertEquals(-6 / 16f, x(a, "Root"), EPS);
        // sitting: no limb swing reaches the animation
        assertEquals(0, a.limbSwingAmount, 0);
    }

    @Test
    void holdSwingAndParallelLayers() {
        PlayerAnimator a = animator(STATES, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 5);
        // empty hand + parallel z rotation
        assertEquals(-10 * DEG, value(a, "RightArm", 0), EPS);
        assertEquals(5 * DEG, value(a, "RightArm", 2), EPS);
        s.mainhand = ItemInfo.of("minecraft:iron_sword", "sword", ItemInfo.UseAction.BLOCK);
        run(a, s, 6, 10);
        assertEquals(-30 * DEG, value(a, "RightArm", 0), EPS);
        // a swing pauses the hold and plays swing_hand
        s.swinging = true;
        s.swingTime = 0;
        run(a, s, 11, 11);
        s.swingTime = 1;
        run(a, s, 12, 12);
        assertEquals(-90 * DEG, value(a, "RightArm", 0), EPS);
        assertEquals(5 * DEG, value(a, "RightArm", 2), EPS);
        s.swinging = false;
        run(a, s, 13, 40);
        assertEquals(-30 * DEG, value(a, "RightArm", 0), EPS);
    }

    @Test
    void armorAndPreParallel() {
        PlayerAnimator a = animator(STATES, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 2);
        assertTrue(
            a.pose()
                .hidden(GEO.indexOf("Hat")));
        s.armor.put(ItemConditions.Slot.HEAD, ItemInfo.of("minecraft:diamond_helmet", "", null));
        run(a, s, 3, 4);
        assertEquals(2, value(a, "Hat", 6), EPS);
        assertEquals(
            MolangValue.ONE,
            a.context()
                .call("ysm", "has_helmet", new MolangValue[0]));
    }

    @Test
    void controllerOrderMatchesUpstream() {
        PlayerAnimator a = animator(STATES, null, null);
        List<String> names = new ArrayList<>();
        for (AnimationController c : a.controllers()) {
            names.add(c.name());
        }
        assertEquals(
            java.util.Arrays.asList(
                "player.pre_parallel_0",
                "player.vehicle",
                "player.main",
                "player.hold_offhand",
                "player.hold_mainhand",
                "player.swing",
                "player.use",
                "player.fishing", // ours, not upstream (FishingStates)
                "player.passenger",
                "player.cap",
                "player.parallel_0",
                "player.armor_head"),
            names);
    }

    @Test
    void extraAnimationsPlayOnTheCapControllerAndEnd() {
        PlayerAnimator a = animator(STATES, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 3);
        assertFalse(a.playExtra("nope"));
        assertTrue(a.playExtra("extra0"));
        run(a, s, 4, 6);
        assertTrue(a.isPlayingExtra());
        assertEquals(7 / 16f, value(a, "Body", 4), EPS);
        assertEquals(
            MolangValue.ONE,
            a.context()
                .call("ctrl", "playing_extra_animation", new MolangValue[0]));
        // 10 ticks long, then 3 ticks blending out
        run(a, s, 7, 30);
        assertFalse(a.isPlayingExtra());
        assertEquals(0, value(a, "Body", 4), EPS);
        // a looping one stops on request
        assertTrue(a.playExtra("idle"));
        run(a, s, 31, 33);
        assertTrue(a.isPlayingExtra());
        a.stopExtra();
        assertFalse(a.isPlayingExtra());
    }

    @Test
    void theHeadFollowsTheLook() {
        PlayerAnimator a = animator(STATES, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        s.pitch = 30;
        s.bodyYaw = 10;
        s.headYaw = 50;
        run(a, s, 0, 1);
        // headPitch = -pitch, netHeadYaw = -(headYaw - bodyYaw)
        assertEquals(-30 * DEG, value(a, "Head", 0), EPS);
        assertEquals(-40 * DEG, value(a, "Head", 1), EPS);
        // only Head (and HeadN) bones
        assertEquals(0, value(a, "Hat", 0), EPS);
        // wrapped to [-180, 180) then clamped to 85 degrees: 190 -> -170 -> -85, negated
        s.headYaw = 200;
        a.update(s, 1); // same time: only the look refreshes
        assertEquals(85 * DEG, value(a, "Head", 1), EPS);
        // Molang sees the same values, with upstream's swapped head_x/head_y
        assertEquals(
            85,
            a.context()
                .call("ysm", "head_yaw", new MolangValue[0])
                .asNumber(),
            EPS);
        assertEquals(
            85,
            a.context()
                .call("query", "head_x_rotation", new MolangValue[0])
                .asNumber(),
            EPS);
        assertEquals(
            -30,
            a.context()
                .call("query", "head_y_rotation", new MolangValue[0])
                .asNumber(),
            EPS);
        assertEquals("Head", ModelAnimations.locatorName("Head2"));
        assertEquals("Head", ModelAnimations.locatorName("Head"));
    }

    @Test
    void missingAnimationsFallBackToTheDefaultLibrary() {
        AnimationLibrary fallback = library(
            "\"walk\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[9,0,0]}}},"
                + "\"idle\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[8,0,0]}}},"
                + "\"parallel3\":{\"loop\":true,\"bones\":{\"Body\":{\"rotation\":[0,0,1]}}}",
            null);
        AnimationLibrary own = library(
            "\"idle\":{\"loop\":true,\"bones\":{\"Root\":{\"position\":[1,0,0]}}}",
            fallback);
        assertFalse(own.resolve("idle").fallback);
        assertTrue(own.resolve("walk").fallback);
        assertNull(own.resolve("run"));
        assertTrue(
            own.names()
                .contains("parallel3"));
        PlayerAnimator a = new PlayerAnimator(ModelAnimations.of(GEO, own, null));
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 5);
        assertEquals(-1 / 16f, x(a, "Root"), EPS);
        s.limbSwingAmount = 0.8f;
        run(a, s, 6, 10);
        assertEquals(-9 / 16f, x(a, "Root"), EPS);
        // the fallback's parallel layers become controllers too
        assertTrue(a.model().parallel.containsKey("player.parallel_3"));
        // a state neither has leaves the main controller idle; the bone eases to rest
        s.sprinting = true;
        run(a, s, 11, 20);
        assertEquals(0, x(a, "Root"), EPS);
    }

    @Test
    void oldModelsLoopHandAnimationsUnlessBorrowed() {
        String clips = "\"use_mainhand\":{\"animation_length\":0.5,\"bones\":{\"RightArm\":{\"position\":[1,0,0]}}}";
        AnimationLibrary fallback = library(
            "\"swing_hand\":{\"loop\":true,\"animation_length\":0.5,\"bones\":{\"RightArm\":{\"position\":[1,0,0]}}}",
            null);
        PlayerAnimator old = animator(clips, fallback, "2.2.2");
        PlayerAnimator current = animator(clips, fallback, null);
        CodedController oldC = new CodedController(
            "t",
            old.model.library,
            new float[GEO.bones.length * 9],
            old.model,
            0,
            false,
            c -> PlayState.CONTINUE);
        CodedController curC = new CodedController(
            "t",
            current.model.library,
            new float[GEO.bones.length * 9],
            current.model,
            0,
            false,
            c -> PlayState.CONTINUE);
        AnimTestSupport.TestContext ctx = new AnimTestSupport.TestContext();
        PlayerControllers.playCompat(old, oldC, "use_mainhand", com.akashic.futura.format.pojo.animation.LoopType.LOOP);
        PlayerControllers
            .playCompat(current, curC, "use_mainhand", com.akashic.futura.format.pojo.animation.LoopType.LOOP);
        oldC.player()
            .process(0, ctx, true);
        curC.player()
            .process(0, ctx, true);
        // own clip, old model: forced to loop; current model: the file's play-once
        oldC.player()
            .process(100, ctx, true);
        curC.player()
            .process(100, ctx, true);
        assertEquals(PlaybackState.RUNNING, oldC.state());
        assertEquals(PlaybackState.ENDING_TRANSITION, curC.state());
        // borrowed from the default: the file's loop type even for old models
        CodedController borrowed = new CodedController(
            "t",
            old.model.library,
            new float[GEO.bones.length * 9],
            old.model,
            0,
            false,
            c -> PlayState.CONTINUE);
        PlayerControllers
            .playCompat(old, borrowed, "swing_hand", com.akashic.futura.format.pojo.animation.LoopType.PLAY_ONCE);
        borrowed.player()
            .process(0, ctx, true);
        borrowed.player()
            .process(100, ctx, true);
        assertEquals(PlaybackState.RUNNING, borrowed.state());
    }

    @Test
    void theBuiltinDefaultLibraryLoads() throws IOException {
        AnimationLibrary defaults = DefaultAnimations.library();
        assertNotNull(defaults, String.valueOf(DefaultAnimations.failure()));
        for (String name : new String[] { "idle", "walk", "run", "jump", "sneak", "swing_hand", "use_mainhand",
            "hold_mainhand:sword", "extra0", "parallel0", "pre_parallel0" }) {
            assertNotNull(defaults.get(name), name);
        }
        // first-person set is not part of the body library
        assertFalse(defaults.resolve("idle").fallback);
        // a built-in model gets the default behind it, the default itself does not
        LoadedModel def = ModelLoader.load(new ClasspathModelSource(DefaultAnimations.BASE));
        assertNull(ModelAnimations.compile(def).library.fallback());
        LoadedModel fox = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/wine_fox/18_wedding/"));
        ModelAnimations foxAnims = ModelAnimations.compile(fox);
        assertNotNull(foxAnims.library.fallback());
        assertTrue(foxAnims.library.contains("swing_hand"));
    }

    @Test
    void molangContextAnswersPlayerQueries() {
        PlayerAnimator a = animator(STATES, null, null);
        PlayerSnapshot s = new PlayerSnapshot();
        s.motionX = 0.3;
        s.motionZ = 0.4;
        s.health = 7;
        s.foodLevel = 9;
        s.mainhand = ItemInfo.of("minecraft:iron_sword", "sword", ItemInfo.UseAction.BLOCK);
        s.uuidLeastSignificantBits = 3;
        run(a, s, 0, 2);
        PlayerMolangContext c = a.context();
        MolangValue[] none = new MolangValue[0];
        assertEquals(
            10,
            c.call("query", "ground_speed", none)
                .asNumber(),
            EPS);
        assertEquals(
            7,
            c.call("query", "health", none)
                .asNumber(),
            EPS);
        assertEquals(
            9,
            c.call("ysm", "food_level", none)
                .asNumber(),
            EPS);
        assertEquals(
            0.1,
            c.call("query", "life_time", none)
                .asNumber(),
            EPS);
        assertEquals(
            1,
            c.call("ysm", "has_mainhand", none)
                .asNumber(),
            0);
        assertNull(c.call("query", "no_such_thing", none));
        MolangValue[] hold = { MolangValue.of("mainhand"), MolangValue.of(":sword") };
        assertEquals(
            1,
            c.call("ctrl", "hold", hold)
                .asNumber(),
            0);
        MolangValue[] holdId = { MolangValue.of("mainhand"), MolangValue.of("$minecraft:iron_sword") };
        assertEquals(
            1,
            c.call("ctrl", "hold", holdId)
                .asNumber(),
            0);
        MolangValue[] wrongSlot = { MolangValue.of("head"), MolangValue.of(":sword") };
        assertNull(c.call("ctrl", "hold", wrongSlot));
        MolangValue[] any = { MolangValue.of("mainhand"), MolangValue.of("minecraft:stick"),
            MolangValue.of("iron_sword") };
        assertEquals(
            1,
            c.call("query", "is_item_name_any", any)
                .asNumber(),
            0);
        // blink: (renderTicks + |uuid lsb| % 10) % 90 in (85, 90)
        run(a, s, 3, 83);
        assertEquals(
            1,
            c.call("ysm", "is_close_eyes", none)
                .asNumber(),
            0); // 83 + 3 = 86
        run(a, s, 84, 90);
        assertEquals(
            0,
            c.call("ysm", "is_close_eyes", none)
                .asNumber(),
            0); // 93 % 90 = 3
        // physics through Molang: first call is the raw input
        MolangValue[] so = { MolangValue.of("tail"), MolangValue.of(4.0), MolangValue.of(2.0) };
        assertEquals(
            4,
            c.call("ysm", "second_order", so)
                .asNumber(),
            0);
        // bone attributes
        c.call(
            "ysm",
            "bone_color",
            new MolangValue[] { MolangValue.of("Head"), MolangValue.of(255.0), MolangValue.of(0.0),
                MolangValue.of(128.0) });
        assertEquals(0xFF0080, a.attributes().color[GEO.indexOf("Head")]);
        // vertical speed from the tracked (interpolated) position: 0.5 blocks in one tick
        s.prevY = 0;
        s.y = 0.5;
        s.partialTicks = 1;
        run(a, s, 91, 91);
        assertEquals(
            10,
            c.call("query", "vertical_speed", none)
                .asNumber(),
            EPS);
        run(a, s, 92, 92);
        assertEquals(
            0,
            c.call("query", "vertical_speed", none)
                .asNumber(),
            EPS);
    }
}

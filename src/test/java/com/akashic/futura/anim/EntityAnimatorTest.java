package com.akashic.futura.anim;

import static com.akashic.futura.anim.AnimTestSupport.DEG;
import static com.akashic.futura.anim.AnimTestSupport.EPS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.model.BuiltinIndex;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ReplacementTarget;
import com.akashic.futura.molang.MolangValue;
import com.akashic.futura.molang.MolangVariables;

/** The animation of projectile and vehicle replacements: controller stacks, predicates, roaming, built-in models. */
class EntityAnimatorTest {

    static final BakedGeometry GEO = AnimTestSupport.geometry("root", "tip:root");

    static EntityAnimations anims(ReplacementTarget.Kind kind, String animationsJson,
        Map<String, ControllerData> controllers, ModelFunctions functions) {
        return EntityAnimations
            .of(kind, GEO, AnimTestSupport.library(animationsJson, null), controllers, functions);
    }

    static List<String> names(EntityAnimator a) {
        List<String> names = new ArrayList<>();
        for (AnimationController c : a.controllers()) {
            names.add(c.name());
        }
        return names;
    }

    /** Runs frames at 1 tick each from {@code from} ticks. */
    static void run(EntityAnimator a, EntitySnapshot s, int from, int frames) {
        for (int i = 0; i < frames; i++) {
            s.ticksExisted = from + i;
            a.update(s, from + i);
        }
    }

    static float rotation(EntityAnimator a, String bone, int axis) {
        return a.pose().values[GEO.indexOf(bone) * BonePose.FLOATS + axis];
    }

    static final String PROJECTILE_ANIMS = "\"air\":{\"loop\":true,\"bones\":{\"tip\":{\"rotation\":[0,0,90]}}},"
        + "\"ground\":{\"loop\":true,\"bones\":{\"tip\":{\"rotation\":[0,0,45]}}},"
        + "\"water\":{\"loop\":true,\"bones\":{\"tip\":{\"rotation\":[0,0,30]}}},"
        + "\"fire\":{\"loop\":true,\"bones\":{\"tip\":{\"rotation\":[0,0,20]}}},"
        + "\"parallel2\":{\"loop\":true,\"bones\":{\"root\":{\"rotation\":[0,0,10]}}},"
        + "\"parallel3\":{\"loop\":true}";

    @Test
    void projectileStackAndMainPredicate() {
        EntityAnimator a = new EntityAnimator(
            anims(
                ReplacementTarget.Kind.PROJECTILE,
                PROJECTILE_ANIMS,
                Collections.<String, ControllerData>emptyMap(),
                ModelFunctions.EMPTY));
        // pre_main/post_main are not declared; parallel3 is empty, so it gets no controller
        assertEquals(java.util.Arrays.asList("projectile.main", "projectile.parallel_2"), names(a));
        EntitySnapshot s = new EntitySnapshot();
        s.onGround = false;
        run(a, s, 0, 20);
        assertEquals("air", a.playing("projectile.main"));
        assertEquals(90 * DEG, rotation(a, "tip", 2), EPS);
        assertEquals(10 * DEG, rotation(a, "root", 2), EPS);
        s.inGround = true;
        run(a, s, 20, 20);
        assertEquals("ground", a.playing("projectile.main"));
        assertEquals(45 * DEG, rotation(a, "tip", 2), EPS);
        s.onFire = true;
        run(a, s, 40, 20);
        assertEquals("fire", a.playing("projectile.main"));
        s.inWater = true;
        run(a, s, 60, 20);
        assertEquals("water", a.playing("projectile.main"));
        assertEquals(30 * DEG, rotation(a, "tip", 2), EPS);
    }

    @Test
    void noMainControllerWhenItsAnimationsAreEmpty() {
        // the default arrow: water, fire, ground, air are all empty clips, only parallel0 moves bones
        EntityAnimator a = new EntityAnimator(
            anims(
                ReplacementTarget.Kind.PROJECTILE,
                "\"water\":{\"loop\":true},\"air\":{\"loop\":true},"
                    + "\"parallel0\":{\"loop\":true,\"bones\":{\"tip\":{\"scale\":0}}}",
                Collections.<String, ControllerData>emptyMap(),
                ModelFunctions.EMPTY));
        assertEquals(Collections.singletonList("projectile.parallel_0"), names(a));
        run(a, new EntitySnapshot(), 0, 5);
        assertTrue(a.pose()
            .hidden(GEO.indexOf("tip")));
    }

    static final String VEHICLE_ANIMS = "\"ground\":{\"loop\":true,\"bones\":{\"root\":{\"position\":[0,2,0]}}},"
        + "\"water\":{\"loop\":true,\"bones\":{\"root\":{\"position\":[0,8,0]}}},"
        + "\"forward\":{\"loop\":true,\"bones\":{\"tip\":{\"rotation\":[0,0,60]}}},"
        + "\"has_ride\":{\"loop\":true,\"bones\":{\"tip\":{\"scale\":2}}},"
        + "\"pre_parallel1\":{\"loop\":true,\"bones\":{\"root\":{\"rotation\":[0,5,0]}}}";

    @Test
    void vehicleStackAndPredicates() {
        EntityAnimator a = new EntityAnimator(
            anims(
                ReplacementTarget.Kind.VEHICLE,
                VEHICLE_ANIMS,
                Collections.<String, ControllerData>emptyMap(),
                ModelFunctions.EMPTY));
        assertEquals(
            java.util.Arrays.asList("vehicle.pre_parallel_1", "vehicle.main", "vehicle.move", "vehicle.ride"),
            names(a));
        EntitySnapshot s = new EntitySnapshot();
        run(a, s, 0, 20);
        assertEquals("ground", a.playing("vehicle.main"));
        assertNull(a.playing("vehicle.move")); // idle: no such clip here
        assertNull(a.playing("vehicle.ride")); // not_ride: no such clip here
        int root = GEO.indexOf("root") * BonePose.FLOATS;
        assertEquals(2 / 16f, a.pose().values[root + 4], EPS);
        int tip = GEO.indexOf("tip") * BonePose.FLOATS;
        assertEquals(0, a.pose().values[tip + 2], EPS);
        s.motionX = 0.3;
        s.hasRider = true;
        s.inWater = true;
        run(a, s, 20, 20);
        assertEquals("water", a.playing("vehicle.main"));
        assertEquals("forward", a.playing("vehicle.move"));
        assertEquals("has_ride", a.playing("vehicle.ride"));
        assertEquals(8 / 16f, a.pose().values[root + 4], EPS);
        assertEquals(60 * DEG, a.pose().values[tip + 2], EPS);
        assertEquals(2, a.pose().values[tip + 6], EPS);
        s.motionX = 0.04;
        run(a, s, 40, 20);
        assertNull(a.playing("vehicle.move"));
        assertEquals(0, a.pose().values[tip + 2], EPS);
    }

    @Test
    void overrideFunctionsAndBedrockControllersDeclareControllers() {
        Map<String, String> sources = new HashMap<>();
        // returns ctrl.state_stop: the coded main predicate never runs
        sources.put("stop@projectile_ctrl_main", "ctrl.state_stop");
        sources.put("x@projectile_ctrl_post_main", "0");
        ModelFunctions functions = ModelFunctions.compile(sources, AnimTestSupport::stubParse);
        EntityAnimator a = new EntityAnimator(
            anims(
                ReplacementTarget.Kind.PROJECTILE,
                PROJECTILE_ANIMS,
                Collections.<String, ControllerData>emptyMap(),
                functions));
        assertEquals(
            java.util.Arrays.asList("projectile.main", "projectile.post_main", "projectile.parallel_2"),
            names(a));
        EntitySnapshot s = new EntitySnapshot();
        run(a, s, 0, 20);
        assertNull(a.playing("projectile.main"));
        assertEquals(0, rotation(a, "tip", 2), EPS);
    }

    @Test
    void theOwnersRoamingVariablesAreCopiedNotShared() {
        EntityAnimator a = new EntityAnimator(
            anims(
                ReplacementTarget.Kind.VEHICLE,
                VEHICLE_ANIMS,
                Collections.<String, ControllerData>emptyMap(),
                ModelFunctions.EMPTY));
        MolangVariables owner = new MolangVariables();
        owner.set("roaming.wheel", MolangValue.of(12.5));
        owner.set("local", MolangValue.of(3));
        a.takeRoaming(owner);
        assertEquals(12.5, a.variables()
            .get("roaming.wheel")
            .asNumber(), 1e-6);
        assertFalse(a.variables()
            .has("local"));
        a.variables()
            .set("roaming.wheel", MolangValue.of(1));
        assertEquals(12.5, owner.get("roaming.wheel")
            .asNumber(), 1e-6);
    }

    @Test
    void molangReadsTheEntity() {
        EntityAnimator a = new EntityAnimator(
            anims(
                ReplacementTarget.Kind.PROJECTILE,
                PROJECTILE_ANIMS,
                Collections.<String, ControllerData>emptyMap(),
                ModelFunctions.EMPTY));
        EntitySnapshot s = new EntitySnapshot();
        s.shootItemId = "minecraft:bow";
        s.motionX = 0.3;
        s.motionZ = 0.4;
        s.inWater = true;
        run(a, s, 0, 2);
        EntityMolangContext ctx = (EntityMolangContext) contextOf(a);
        assertEquals("minecraft:bow", ctx.call("ysm", "shoot_item_id", new MolangValue[0])
            .asString());
        assertEquals(10, ctx.call("query", "ground_speed", new MolangValue[0])
            .asNumber(), 1e-4);
        assertTrue(ctx.call("query", "is_in_water", new MolangValue[0])
            .asBoolean());
        assertEquals(0, ctx.call("ctrl", "idle", new MolangValue[0])
            .asNumber(), 0);
        assertNull(ctx.call("query", "no_such_query", new MolangValue[0]));
    }

    private static AnimationContext contextOf(EntityAnimator a) {
        try {
            java.lang.reflect.Field f = EntityAnimator.class.getDeclaredField("context");
            f.setAccessible(true);
            return (AnimationContext) f.get(a);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- built-in models ----

    @Test
    void theDefaultArrowShowsTheBowOrTheCrossbow() throws IOException {
        LoadedModel def = ModelLoader.load(BuiltinIndex.source("default"));
        ReplacementTarget target = def.projectile("minecraft:arrow");
        EntityAnimations anims = EntityAnimations.compile(def, target, null, new ClipCompiler());
        int bow = anims.geometry.indexOf("bow");
        int crossbow = anims.geometry.indexOf("crossbow");
        EntityAnimator a = new EntityAnimator(anims);
        EntitySnapshot s = new EntitySnapshot();
        s.shootItemId = "minecraft:bow";
        run(a, s, 0, 3);
        assertFalse(a.pose()
            .hidden(bow));
        assertTrue(a.pose()
            .hidden(crossbow));
        EntityAnimator b = new EntityAnimator(anims);
        s.shootItemId = "minecraft:crossbow";
        run(b, s, 0, 3);
        assertTrue(b.pose()
            .hidden(bow));
        assertFalse(b.pose()
            .hidden(crossbow));
    }

    @Test
    void aTargetBorrowsTheDefaultTargetOfTheSameMatchList() throws IOException {
        LoadedModel maid = ModelLoader.load(BuiltinIndex.source("wine_fox/01_taisho_maid"));
        EntityAnimations arrow = EntityAnimations.compile(maid, maid.projectile("minecraft:arrow"));
        // the maid's arrow animations have parallel0; "air" comes from the default arrow
        assertTrue(arrow.library.containsLocal("parallel0"));
        assertFalse(arrow.library.containsLocal("air"));
        assertTrue(arrow.library.contains("air"));
        // no default target for horses
        EntityAnimations horse = EntityAnimations.compile(maid, maid.vehicle("minecraft:horse"));
        assertNull(horse.library.fallback());
        assertNotNull(DefaultAnimations.replacementLibrary(maid.vehicle("minecraft:boat")
            .domain()));
    }

    @TestFactory
    Stream<DynamicTest> everyBuiltinReplacementAnimates() throws IOException {
        Path builtin = Paths.get("src/main/resources/assets/futura3d/builtin");
        List<Path> manifests;
        try (Stream<Path> walk = Files.walk(builtin)) {
            manifests = walk.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList());
        }
        return manifests.stream()
            .map(p -> {
                String rel = builtin.relativize(p.getParent())
                    .toString()
                    .replace('\\', '/');
                return DynamicTest.dynamicTest(rel, () -> animate(rel));
            });
    }

    private static void animate(String rel) throws IOException {
        LoadedModel model = ModelLoader.load(BuiltinIndex.source(rel));
        for (ReplacementTarget target : model.replacements()) {
            EntityAnimator a = new EntityAnimator(EntityAnimations.compile(model, target));
            EntitySnapshot s = new EntitySnapshot();
            s.shootItemId = "minecraft:bow";
            for (int frame = 0; frame < 180; frame++) {
                int tick = frame / 3;
                s.ticksExisted = tick;
                s.partialTicks = (frame % 3) / 3f;
                s.prevX = s.x;
                s.x += 0.1;
                s.motionX = frame < 90 ? 0.2 : 0;
                s.hasRider = frame % 60 < 30;
                s.inWater = frame > 120;
                s.inGround = frame > 150;
                a.update(s, tick + s.partialTicks);
                for (float v : a.pose().values) {
                    assertFalse(Float.isNaN(v) || Float.isInfinite(v), rel + " " + target + " frame " + frame);
                }
            }
            a.stopSounds();
        }
    }
}

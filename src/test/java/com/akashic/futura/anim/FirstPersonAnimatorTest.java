package com.akashic.futura.anim;

import static com.akashic.futura.anim.AnimTestSupport.DEG;
import static com.akashic.futura.anim.AnimTestSupport.EPS;
import static com.akashic.futura.anim.AnimTestSupport.geometry;
import static com.akashic.futura.anim.AnimTestSupport.library;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.molang.MolangValue;

/** The first-person arms: their own controllers on the arm geometry, roaming shared with the body. */
class FirstPersonAnimatorTest {

    private static final BakedGeometry ARM = geometry(
        "Arm",
        "LeftArm:Arm",
        "RightArm:Arm",
        "Fox:RightArm",
        "Background",
        "Glove:RightArm");

    private static final String FP = "\"parallel0\":{\"loop\":true,\"bones\":{\"Fox\":{\"rotation\":[0,0,5]}}},"
        + "\"parallel2\":{\"loop\":true,\"bones\":{\"Fox\":{\"rotation\":[0,0,1]}}},"
        + "\"parallel3\":{\"loop\":true,\"bones\":{}},"
        + "\"chest$minecraft:diamond_chestplate\":{\"loop\":true,\"bones\":{\"Glove\":{\"scale\":[2,2,2]}}},"
        + "\"hold_mainhand:empty\":{\"loop\":true,\"bones\":{\"RightArm\":{\"rotation\":[40,0,0]}}}";

    private static PlayerAnimator arms(String animations) {
        PlayerAnimator body = new PlayerAnimator(ModelAnimations.of(geometry("Root"), library("", null), null));
        return PlayerAnimator.firstPerson(ModelAnimations.firstPersonOf(ARM, library(animations, null), null), body);
    }

    private static void run(PlayerAnimator a, PlayerSnapshot s, int from, int to) {
        for (int t = from; t <= to; t++) {
            s.ticksExisted = t;
            a.update(s, t);
        }
    }

    private static float value(PlayerAnimator a, String bone, int index) {
        return a.pose().values[ARM.indexOf(bone) * BonePose.FLOATS + index];
    }

    @Test
    void partRootsAreFoundByName() {
        ModelAnimations m = ModelAnimations.firstPersonOf(ARM, library(FP, null), null);
        assertTrue(m.firstPerson);
        assertEquals(ARM.indexOf("LeftArm"), m.leftArm);
        assertEquals(ARM.indexOf("RightArm"), m.rightArm);
        assertEquals(ARM.indexOf("Background"), m.background);
        // the body has no first-person parts, whatever its bones are called
        ModelAnimations body = ModelAnimations.of(ARM, library(FP, null), null);
        assertEquals(-1, body.rightArm);
    }

    @Test
    void controllerOrderMatchesUpstream() {
        PlayerAnimator a = arms(FP);
        List<String> names = new ArrayList<>();
        for (AnimationController c : a.controllers()) {
            names.add(c.name());
        }
        // empty clips get no parallel controller; no main/hold/swing/use/cap in first person
        assertEquals(Arrays.asList("fp.arm.parallel_0", "fp.arm.parallel_2", "fp.arm.armor_chest"), names);
    }

    @Test
    void parallelLayersAddAndHoldNamesDoNotPlay() {
        PlayerAnimator a = arms(FP);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 5);
        assertEquals(6 * DEG, value(a, "Fox", 2), EPS);
        // hold_mainhand:* is a body animation: the arms have no controller for it
        assertEquals(0, value(a, "RightArm", 0), EPS);
        assertFalse(a.playExtra("extra0"));
    }

    @Test
    void armorPlaysOnTheArms() {
        PlayerAnimator a = arms(FP);
        PlayerSnapshot s = new PlayerSnapshot();
        run(a, s, 0, 2);
        assertEquals(1, value(a, "Glove", 6), EPS);
        s.armor.put(ItemConditions.Slot.CHEST, ItemInfo.of("minecraft:diamond_chestplate", "", null));
        run(a, s, 3, 4);
        assertEquals(2, value(a, "Glove", 6), EPS);
    }

    @Test
    void roamingVariablesAreTheBodys() {
        PlayerAnimator body = new PlayerAnimator(ModelAnimations.of(geometry("Root"), library("", null), null));
        PlayerAnimator arm = PlayerAnimator
            .firstPerson(ModelAnimations.firstPersonOf(ARM, library(FP, null), null), body);
        body.variables()
            .set("roaming.hat", MolangValue.ONE);
        arm.variables()
            .set("local", MolangValue.ONE);
        assertEquals(
            MolangValue.ONE,
            arm.variables()
                .get("roaming.hat"));
        assertFalse(
            body.variables()
                .has("local"));
        assertThrows(
            IllegalArgumentException.class,
            () -> PlayerAnimator.firstPerson(ModelAnimations.of(ARM, library(FP, null), null), body));
    }

    @Test
    void defaultModelArms() throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource(DefaultAnimations.BASE));
        ModelAnimations arms = ModelAnimations.compileFirstPerson(model);
        assertTrue(arms.rightArm >= 0);
        assertTrue(arms.leftArm >= 0);
        assertEquals(-1, arms.background);
        assertEquals("RightArm", arms.geometry.bones[arms.rightArm].name);
        // only fp_arm: its parallel clips are there (empty), the body's are not
        assertNotNull(arms.library.get("parallel0"));
        assertNull(arms.library.get("idle"));
        assertNull(arms.library.get("hold_mainhand:sword"));
        assertTrue(arms.parallel.isEmpty());
        assertNotNull(DefaultAnimations.firstPersonLibrary());
    }

    @Test
    void otherModelsFallBackToTheDefaultArms() throws IOException {
        LoadedModel model = ModelLoader
            .load(new ClasspathModelSource("/assets/futura3d/builtin/wine_fox/19_nine_tailed/"));
        ModelAnimations arms = ModelAnimations.compileFirstPerson(model);
        assertTrue(arms.library.containsLocal("parallel0"));
        // parallel1 comes from the default model's fp_arm
        assertTrue(arms.library.resolve("parallel1").fallback);
        assertEquals(
            "fp.arm.parallel_0",
            arms.parallel.keySet()
                .iterator()
                .next());
    }

    @TestFactory
    Stream<DynamicTest> everyBuiltinModelAnimatesItsArms() throws IOException {
        Path builtin = Paths.get("src/main/resources/assets/futura3d/builtin");
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(builtin)) {
            manifests = files.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList());
        }
        return manifests.stream()
            .map(m -> {
                String rel = builtin.relativize(m.getParent())
                    .toString()
                    .replace('\\', '/');
                return DynamicTest.dynamicTest(rel, () -> animate(rel));
            });
    }

    private static void animate(String rel) throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + rel + "/"));
        PlayerAnimator body = new PlayerAnimator(ModelAnimations.compile(model));
        PlayerAnimator arms = PlayerAnimator.firstPerson(ModelAnimations.compileFirstPerson(model), body);
        assertTrue(arms.model().rightArm >= 0, rel + " has a RightArm");
        PlayerSnapshot s = new PlayerSnapshot();
        s.firstPerson = true;
        for (int frame = 0; frame < 120; frame++) {
            int tick = frame / 3;
            s.ticksExisted = tick;
            s.partialTicks = (frame % 3) / 3f;
            s.limbSwingAmount = frame > 60 ? 0.8f : 0;
            arms.update(s, tick + s.partialTicks);
            float[] v = arms.pose().values;
            for (int i = 0; i < v.length; i++) {
                assertFalse(Float.isNaN(v[i]) || Float.isInfinite(v[i]), rel + " frame " + frame + " value " + i);
            }
        }
    }
}

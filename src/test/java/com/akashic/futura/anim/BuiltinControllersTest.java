package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

/** Every built-in model with Bedrock controllers or functions, animated through hands, swings, uses and emotes. */
class BuiltinControllersTest {

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    private static LoadedModel load(String rel) throws IOException {
        return ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + rel + "/"));
    }

    @TestFactory
    Stream<DynamicTest> modelsWithControllersOrFunctionsAnimate() throws IOException {
        List<String> models = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BUILTIN)) {
            for (Path m : files.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList())) {
                String rel = BUILTIN.relativize(m.getParent())
                    .toString()
                    .replace('\\', '/');
                LoadedModel model = load(rel);
                if (!model.controllers.isEmpty() || !model.functions.isEmpty()) {
                    models.add(rel);
                }
            }
        }
        assertTrue(models.size() >= 15, "models with controllers or functions: " + models);
        return models.stream()
            .map(rel -> DynamicTest.dynamicTest(rel, () -> animate(rel)));
    }

    private static void animate(String rel) throws IOException {
        LoadedModel model = load(rel);
        ModelAnimations anims = ModelAnimations.compile(model);
        assertEquals(model.controllers.size(), anims.controllers.size());
        PlayerAnimator animator = new PlayerAnimator(anims);
        PlayerAnimator arms = PlayerAnimator.firstPerson(ModelAnimations.compileFirstPerson(model), animator);
        Tier2Support.Sounds sounds = new Tier2Support.Sounds();
        animator.setSoundBackend(sounds);
        PlayerSnapshot s = new PlayerSnapshot();
        s.uuidLeastSignificantBits = rel.hashCode();
        float limb = 0;
        for (int frame = 0; frame < 600; frame++) {
            int tick = frame / 3;
            float pt = (frame % 3) / 3f;
            s.ticksExisted = tick;
            s.partialTicks = pt;
            s.prevX = s.x;
            s.limbSwingAmount = 0;
            s.sprinting = false;
            s.swinging = false;
            s.usingItem = false;
            s.riding = false;
            s.mainhand = ItemInfo.EMPTY;
            s.onGround = true;
            if (frame >= 60 && frame < 140) {
                s.limbSwingAmount = 0.6f;
            } else if (frame >= 140 && frame < 200) {
                s.limbSwingAmount = 1f;
                s.sprinting = true;
            } else if (frame >= 200 && frame < 300) {
                s.mainhand = ItemInfo.of("minecraft:iron_sword", "sword", ItemInfo.UseAction.BLOCK);
                s.swinging = frame >= 240;
                s.swingTime = (frame - 240) / 3 % 6;
            } else if (frame >= 300 && frame < 380) {
                s.mainhand = ItemInfo.of("minecraft:bow", "bow", ItemInfo.UseAction.BOW);
                s.usingItem = frame < 360;
                s.ticksUsingItem = (frame - 300) / 3 + 1;
            } else if (frame >= 380 && frame < 420) {
                s.onGround = false;
            } else if (frame >= 420 && frame < 460) {
                s.riding = true;
                s.vehicleId = "minecraft:boat";
                s.vehicleKind = PlayerInputs.VehicleKind.BOAT;
            } else if (frame == 460) {
                animator.playExtra("extra0");
            }
            limb += s.limbSwingAmount * 0.3f;
            s.limbSwing = limb;
            s.x += s.limbSwingAmount * 0.05;
            s.headYaw = (float) Math.sin(frame * 0.05) * 60;
            s.pitch = (float) Math.cos(frame * 0.07) * 40;
            animator.update(s, tick + pt);
            arms.update(s, tick + pt);
            check(rel, frame, animator.pose().values);
            check(rel, frame, arms.pose().values);
        }
        for (String name : model.controllers.keySet()) {
            AnimationController c = animator.controller(name);
            if (c instanceof HybridController) {
                HybridController h = (HybridController) c;
                assertNotNull(h.bedrock(), rel + " " + name);
                ControllerData data = anims.controllers.get(name);
                if (data.states.containsKey(data.initialState)) {
                    assertNotEquals("(null)", h.stateName(), rel + " " + name);
                } else {
                    // an empty declaration ({}) replaces the coded controller with nothing, as upstream
                    assertEquals(PlaybackState.IDLE, h.state(), rel + " " + name);
                }
            }
        }
        animator.stopSounds();
        assertEquals(0, sounds.playing(), rel + " sounds left playing");
    }

    private static void check(String rel, int frame, float[] v) {
        for (int i = 0; i < v.length; i++) {
            assertFalse(Float.isNaN(v[i]) || Float.isInfinite(v[i]), rel + " frame " + frame + " value " + i);
        }
    }

    @Test
    void defaultControllersModelDrivesTheOffhandAndSwingFromItsFiles() throws IOException {
        LoadedModel model = load("misc/4_default_controllers");
        assertEquals(5, model.controllers.size());
        PlayerAnimator a = new PlayerAnimator(ModelAnimations.compile(model));
        List<String> names = new ArrayList<>();
        for (AnimationController c : a.controllers()) {
            names.add(c.name());
        }
        // post_* exist because they are declared, right after their coded controller
        assertEquals(names.indexOf("player.swing") + 1, names.indexOf("player.post_swing"));
        assertEquals(names.indexOf("player.hold_mainhand") + 1, names.indexOf("player.post_hold"));
        PlayerSnapshot s = new PlayerSnapshot();
        Tier2Support.run(a, s, 0, 10);
        HybridController offhand = (HybridController) a.controller("player.hold_offhand");
        assertNotNull(offhand.bedrock());
        assertEquals("default", offhand.stateName());
        HybridController postHold = (HybridController) a.controller("player.post_hold");
        String idle = postHold.stateName();
        s.mainhand = ItemInfo.of("minecraft:iron_sword", "sword", ItemInfo.UseAction.BLOCK);
        Tier2Support.run(a, s, 11, 30);
        assertNotEquals(idle, postHold.stateName(), "post_hold follows the sword");
    }

    @Test
    void functionsAndSoundsAreFoundInTheModelFolders() throws IOException {
        LoadedModel saint = load("wine_fox/21_saint");
        assertTrue(saint.functions.containsKey("ArrowShoot@player_ctrl_use"));
        assertEquals("sounds/magic01_shoot.ogg", saint.sounds.get("magic01_shoot"));
        ModelAnimations anims = ModelAnimations.compile(saint);
        HybridController use = (HybridController) new PlayerAnimator(anims).controller("player.use");
        assertNotNull(
            use.coded()
                .override());
        // player_init ran the physics set-up: the springs exist and v.s is set
        PlayerAnimator a = new PlayerAnimator(anims);
        Tier2Support.run(a, new PlayerSnapshot(), 0, 2);
        assertEquals(72, Tier2Support.var(a, "s"), 0);
    }
}

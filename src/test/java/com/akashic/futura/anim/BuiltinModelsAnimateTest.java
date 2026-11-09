package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

/**
 * Smoke test: every built-in model animates for a few hundred frames of idle, walk, run and a few actions without
 * exceptions or NaN in the pose. Molang is the placeholder here, so expressions are 0; the point is the machinery.
 */
class BuiltinModelsAnimateTest {

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    @TestFactory
    Stream<DynamicTest> everyBuiltinModelAnimates() throws IOException {
        List<Path> manifests;
        try (Stream<Path> files = Files.walk(BUILTIN)) {
            manifests = files.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .sorted()
                .collect(Collectors.toList());
        }
        assertTrue(manifests.size() >= 20, "built-in models found: " + manifests.size());
        return manifests.stream()
            .map(m -> {
                String rel = BUILTIN.relativize(m.getParent())
                    .toString()
                    .replace('\\', '/');
                return DynamicTest.dynamicTest(rel, () -> animate(rel));
            });
    }

    private static void animate(String rel) throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + rel + "/"));
        ModelAnimations anims = ModelAnimations.compile(model);
        PlayerAnimator animator = new PlayerAnimator(anims);
        PlayerSnapshot s = new PlayerSnapshot();
        s.uuidLeastSignificantBits = rel.hashCode();
        float limb = 0;
        for (int frame = 0; frame < 450; frame++) {
            // three frames per tick
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
            if (frame >= 100 && frame < 200) {
                s.limbSwingAmount = 0.6f;
            } else if (frame >= 200 && frame < 300) {
                s.limbSwingAmount = 1f;
                s.sprinting = true;
            } else if (frame >= 300 && frame < 330) {
                s.mainhand = ItemInfo.of("minecraft:iron_sword", "sword", ItemInfo.UseAction.BLOCK);
                s.swinging = true;
                s.swingTime = (frame - 300) / 3 % 6;
            } else if (frame >= 330 && frame < 360) {
                s.mainhand = ItemInfo.of("minecraft:bow", "bow", ItemInfo.UseAction.BOW);
                s.usingItem = true;
                s.ticksUsingItem = (frame - 330) / 3 + 1;
            } else if (frame >= 360 && frame < 390) {
                s.riding = true;
                s.vehicleId = "minecraft:boat";
                s.vehicleKind = PlayerInputs.VehicleKind.BOAT;
            } else if (frame == 390) {
                animator.playExtra("extra0");
            }
            limb += s.limbSwingAmount * 0.3f;
            s.limbSwing = limb;
            s.x += s.limbSwingAmount * 0.05;
            s.headYaw = (float) Math.sin(frame * 0.05) * 60;
            s.pitch = (float) Math.cos(frame * 0.07) * 40;
            animator.update(s, tick + pt);
            float[] v = animator.pose().values;
            for (int i = 0; i < v.length; i++) {
                assertFalse(Float.isNaN(v[i]) || Float.isInfinite(v[i]), rel + " frame " + frame + " value " + i);
            }
        }
    }
}

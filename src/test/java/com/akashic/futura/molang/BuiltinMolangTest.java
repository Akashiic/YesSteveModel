package com.akashic.futura.molang;

import static com.akashic.futura.molang.MolangLanguageTest.num;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** Real expressions from the built-in models, and every string of their animation and controller files. */
class BuiltinMolangTest {

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    /**
     * Keys whose string values are not Molang: interpolation modes, format versions, loop modes, sound and particle
     * effect names, locators, and the animation names a controller state plays.
     */
    private static final Set<String> NOT_MOLANG = new HashSet<>(
        Arrays.asList("lerp_mode", "format_version", "loop", "effect", "locator", "animations", "initial_state"));

    /** {@code ysm.second_order(name, value, ...)} as identity, enough to check what reaches it. */
    private static FakeContext models() {
        return new FakeContext().function("ysm.second_order", args -> args[1]);
    }

    @Test
    void hairPhysics() {
        FakeContext context = models().value("query.vertical_speed", -1)
            .value("query.ground_speed", 3);
        context.variables.set("hv", MolangValue.of(2));
        context.variables.set("hg", MolangValue.of(1));
        String source = "ysm.second_order('头发垂直', math.clamp(-5*q.vertical_speed+v.hv,-10,150), 1.5, 0.6, 0)"
            + "+ysm.second_order('头发水平', math.clamp(q.ground_speed+v.hg,0,20), 1.5, 0.6, 0)";
        assertEquals(11, num(source, context));
        assertTrue(
            context.calls.stream()
                .anyMatch(call -> call.startsWith("ysm.second_order['头发垂直', 7, 1.5, ")),
            context.calls.toString());
    }

    @Test
    void hairPitch() {
        FakeContext context = models().value("ysm.head_pitch", -60);
        String source = "math.max(ysm.head_pitch,ysm.second_order('winefox_jk_hair_pitch',"
            + "28*0.5/2/math.pi/2*math.max(ysm.head_pitch,-40),2,0.5,0))";
        assertEquals(-40 * 14 / 2 / Math.PI / 2, num(source, context), 1e-4);
        context.value("ysm.head_pitch", 10);
        assertEquals(10 * 14 / 2 / Math.PI / 2, num(source, context), 1e-4);
    }

    @Test
    void angleWrapStatement() {
        FakeContext context = new FakeContext();
        context.variables.set("delta", MolangValue.of(270));
        num("v.delta = v.delta > 180 ? v.delta - 360 : (v.delta < -180 ? v.delta + 360 : v.delta); ", context);
        assertEquals(
            -90,
            context.variables.get("delta")
                .asNumber());
    }

    @Test
    void binaryConditionalCounters() {
        String source = "v.qh=!v.jump?v.qh2+1+(v.qh2>=2?-v.qh2);";
        FakeContext context = new FakeContext();
        context.variables.set("qh2", MolangValue.of(2));
        num(source, context);
        assertEquals(
            1,
            context.variables.get("qh")
                .asNumber());
        context.variables.set("qh2", MolangValue.of(1));
        num(source, context);
        assertEquals(
            2,
            context.variables.get("qh")
                .asNumber());
        // jumping: the conditional is missing, so v.qh becomes missing
        context.variables.set("jump", MolangValue.ONE);
        num(source, context);
        assertFalse(context.variables.has("qh"));

        String combo = "(v.combo==1?(0)) + (v.combo==2?(0)) + (v.combo==3?(0)) + (v.combo==4?(0)) + (v.combo==5?(0))"
            + " + (v.combo==6?(0)) + (v.combo==7?(1)) + (v.combo==9?(0)) + (v.combo==8?(0)) + (v.combo==10?(0));";
        context.variables.set("combo", MolangValue.of(7));
        assertEquals(1, num(combo, context));
        context.variables.set("combo", MolangValue.of(3));
        assertEquals(0, num(combo, context));
    }

    @Test
    void controllerConditionWithStrings() {
        String source = "(ctrl.fly||ctrl.elytra_fly||q.is_in_water||(ctrl.parcool_state!='')||ctrl.ladder_up"
            + "||ctrl.ladder_down||ctrl.ladder_stillness||(ctrl.slashblade_animation!='')||ctrl.carryon_is_princess)";
        FakeContext context = new FakeContext();
        assertEquals(0, num(source, context));
        context.value("ctrl.parcool_state", "");
        assertEquals(0, num(source, context));
        context.value("ctrl.parcool_state", "roll");
        assertEquals(1, num(source, context));
    }

    @Test
    void vectorVariableAndParticles() {
        List<MolangValue[]> particles = new ArrayList<>();
        FakeContext context = new FakeContext().function("ysm.bone_pivot_abs", args -> MolangValue.vector(24, 48, 72))
            .function("ysm.particle", args -> {
                particles.add(args.clone());
                return null;
            });
        num("v.SwordLocate = ysm.bone_pivot_abs('SwordLocater');", context);
        num(
            "ysm.particle('minecraft:cherry_leaves', v.SwordLocate.x / 24,v.SwordLocate.y / 24,"
                + "v.SwordLocate.z / -24, 0, 0, 0, 3, 1,100);",
            context);
        assertEquals(1, particles.size());
        MolangValue[] args = particles.get(0);
        assertEquals(10, args.length);
        assertEquals("minecraft:cherry_leaves", args[0].asString());
        assertEquals(1, args[1].asNumber());
        assertEquals(2, args[2].asNumber());
        assertEquals(-3, args[3].asNumber());
    }

    @Test
    void animTimeAndControllerQueries() {
        FakeContext context = models().value("query.anim_time", 0.75)
            .value("ctrl.im_delta", 50);
        String source = "-23.7636+math.sin(q.anim_time*120)*2+0.5*ysm.second_order('delta',0.1*math.min(100,ctrl.im_delta),1.5,1,1)";
        assertEquals(-23.7636 + 2 + 2.5, num(source, context), 1e-4);
        context.function("ysm.bone_rot", args -> MolangValue.vector(5, 6, 7));
        assertEquals(5, num("ysm.bone_rot('fakehair').x", context));
    }

    @Test
    void conditionalAssignments() {
        String source = "v.kuojianall==0?(v.eye_target_y_rotation_1=q.eye_target_y_rotation):"
            + "(v.eye_target_y_rotation_1=v.eye_target_y_rotation_1);";
        FakeContext context = new FakeContext().value("query.eye_target_y_rotation", 33);
        num(source, context);
        assertEquals(
            33,
            context.variables.get("eye_target_y_rotation_1")
                .asNumber());
        context.variables.set("kuojianall", MolangValue.ONE);
        context.value("query.eye_target_y_rotation", 44);
        num(source, context);
        assertEquals(
            33,
            context.variables.get("eye_target_y_rotation_1")
                .asNumber());
    }

    @Test
    void zeroOverZeroFailsTheWholeChannel() {
        // From the built-in arm animations. Standing still makes vertical_speed/abs(vertical_speed) 0/0: upstream's
        // NaN result fails the program, so the channel is 0 even though the ground-speed term is not (the older YSM
        // divided by 1 instead).
        String source = "((1-math.exp(-0.1*query.ground_speed))*60)-(query.vertical_speed/math.abs(query.vertical_speed)"
            + "*(1-math.exp(-0.1*math.abs(query.vertical_speed)))*30)";
        FakeContext context = new FakeContext().value("query.ground_speed", 5)
            .value("query.vertical_speed", 0);
        assertEquals(0, num(source, context));
        context.value("query.vertical_speed", -2);
        double expected = (1 - Math.exp(-0.5)) * 60 + (1 - Math.exp(-0.2)) * 30;
        assertEquals(expected, num(source, context), 1e-3);
    }

    @Test
    void roamingTimeline() {
        FakeContext context = new FakeContext();
        num("v.roaming.a=1-v.roaming.b;", context);
        num("v.roaming.b=v.roaming.a;", context);
        assertEquals(1, num("0+v.roaming.a", context));
        assertEquals(
            1,
            context.variables.get("roaming.b")
                .asNumber());
    }

    /** Every string in every built-in animation and controller file compiles (unless it is not Molang at all). */
    @Test
    void everyBuiltinStringParses() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(BUILTIN)) {
            files = walk.filter(BuiltinMolangTest::isAnimationOrController)
                .sorted()
                .collect(Collectors.toList());
        }
        assertTrue(files.size() >= 40, "animation and controller files: " + files.size());
        Set<String> sources = new TreeSet<>();
        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                collect(new JsonParser().parse(reader), null, sources);
            }
        }
        assertTrue(sources.size() > 1000, "distinct strings: " + sources.size());
        List<String> failures = new ArrayList<>();
        int constant = 0;
        for (String source : sources) {
            try {
                if (Molang.compile(source)
                    .isConstant()) {
                    constant++;
                }
            } catch (MolangSyntaxException e) {
                failures.add(e.getMessage() + ": " + source);
            }
            // evaluation must not throw either, whatever the host answers
            Molang.parse(source)
                .evaluate(new FakeContext());
        }
        assertTrue(
            failures.isEmpty(),
            failures.size() + " of " + sources.size() + " fail:\n" + String.join("\n", failures));
        assertTrue(constant > 0);
    }

    private static boolean isAnimationOrController(Path path) {
        String name = path.getFileName()
            .toString();
        String parent = path.getParent() == null ? "" : path.toString();
        return name.endsWith(".json") && (parent.contains("/animations/") || parent.contains("/controller/"));
    }

    private static void collect(JsonElement element, String key, Set<String> out) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject()
                .entrySet()) {
                collect(entry.getValue(), entry.getKey(), out);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collect(child, key, out);
            }
        } else if (element.isJsonPrimitive() && element.getAsJsonPrimitive()
            .isString() && (key == null || !NOT_MOLANG.contains(key))) {
                out.add(element.getAsString());
            }
    }
}

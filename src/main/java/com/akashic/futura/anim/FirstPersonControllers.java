/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/collections/FPArmControllerCollection.java and
 * src/main/java/com/elfmcys/ysm/client/animation/predicate/FPArmArmorPredicate.java, Apache License 2.0.
 * Changes: Minecraft reads go through PlayerInputs.
 */
package com.akashic.futura.anim;

import java.util.List;
import java.util.Map;

/**
 * Builds the controller stack of the first-person arms in upstream order:
 *
 * <pre>
 * fp.arm.misc (declared only)  fp.arm.parallel_* (0 s, additive rotation)  fp.arm.armor_* (0 s)
 * </pre>
 *
 * There is no main, hold, swing or use controller: upstream drives the arms only with these (the {@code arm} animation
 * set, despite its name, holds the third-person hand animations). Vanilla's own first-person bobbing, equip and swing
 * transforms move the arms as a whole. As for the body, each is a {@link HybridController}: the model's Bedrock
 * controller of the same name replaces the coded one; {@code fp.arm.misc} exists only when the model declares it, and
 * {@code fp.arm.parallel_*} also for a declared controller without an animation of that number.
 */
public final class FirstPersonControllers {

    public static final String CATEGORY = ModelAnimations.FIRST_PERSON_CATEGORY;
    public static final String MISC = CATEGORY + ".misc";

    private FirstPersonControllers() {}

    static List<AnimationController> build(PlayerAnimator a, ModelAnimations m, float[] committed) {
        PlayerControllers.Builder b = new PlayerControllers.Builder(m, committed);
        // SingleControllerDiscovery(hybrid): a declared controller (an override event cannot name it: "fp.arm" has a
        // dot)
        if (m.controllers.containsKey(MISC)) {
            b.hybrid(MISC, 0, false, PlayerControllers.Builder.EMPTY);
        }
        for (Map.Entry<String, String> e : m.parallel.entrySet()) {
            String anim = e.getValue();
            b.hybrid(
                e.getKey(),
                0,
                true,
                anim != null ? c -> PlayerControllers.loop(c, anim) : PlayerControllers.Builder.EMPTY);
        }
        for (ItemConditions.Slot slot : m.armorSlots) {
            // FPArmArmorPredicate is the body's ArmorPredicate against the arm's own conditions and library
            b.hybrid(CATEGORY + ".armor_" + slot.id, 0, false, c -> PlayerControllers.armor(a, c, slot));
        }
        return b.list;
    }
}

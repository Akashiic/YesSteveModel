/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/collections/FPArmControllerCollection.java and
 * src/main/java/com/elfmcys/ysm/client/animation/predicate/FPArmArmorPredicate.java, Apache License 2.0.
 * Changes: Minecraft reads go through PlayerInputs; the controllers that only exist when the model's arm controller
 * files declare them ({@code fp.arm.misc}, file-declared {@code fp.arm.parallel_*}) are Tier 2.
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the controller stack of the first-person arms in upstream order:
 *
 * <pre>
 * fp.arm.misc (Tier 2)  fp.arm.parallel_0..7 (0 s, additive rotation)  fp.arm.armor_feet/legs/chest/head (0 s)
 * </pre>
 *
 * There is no main, hold, swing or use controller: upstream drives the arms only with these (the {@code arm} animation
 * set, despite its name, holds the third-person hand animations). Vanilla's own first-person bobbing, equip and swing
 * transforms move the arms as a whole.
 * <p>
 * Tier 2 seam: {@code fp.arm.misc} is a {@code HybridAnimationController} with an always-stopping predicate, so it only
 * exists (and only plays) when the model's arm controller files declare a Bedrock controller of that name; it goes
 * first in the list.
 */
public final class FirstPersonControllers {

    public static final String CATEGORY = ModelAnimations.FIRST_PERSON_CATEGORY;

    private FirstPersonControllers() {}

    static List<AnimationController> build(PlayerAnimator a, ModelAnimations m, float[] committed) {
        List<AnimationController> list = new ArrayList<>();
        AnimationLibrary lib = m.library;
        for (Map.Entry<String, String> e : m.parallel.entrySet()) {
            String anim = e.getValue();
            list.add(new CodedController(e.getKey(), lib, committed, m, 0, true, c -> PlayerControllers.loop(c, anim)));
        }
        for (ItemConditions.Slot slot : m.armorSlots) {
            // FPArmArmorPredicate is the body's ArmorPredicate against the arm's own conditions and library
            list.add(
                new CodedController(
                    CATEGORY + ".armor_" + slot.id,
                    lib,
                    committed,
                    m,
                    0,
                    false,
                    c -> PlayerControllers.armor(a, c, slot)));
        }
        return list;
    }
}

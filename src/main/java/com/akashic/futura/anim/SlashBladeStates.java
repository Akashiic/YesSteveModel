/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/compat/slashblade/SlashBladeAnimation.java (playMainAnimation: the main state
 * retried as slashblade:<state>), SlashBladeResharped.java (combo names, NAME_FIX) and
 * src/main/java/com/elfmcys/ysm/client/animation/predicate/SwingPredicate.java (the combo on the swing controller),
 * Apache License 2.0.
 * Changes: the blade and its combo state come from external states another mod sets (Kunftig's SlashBlade-style
 * blades on 1.7.10) instead of SlashBlade's capability; a combo restarts when its generation changes.
 */
package com.akashic.futura.anim;

import java.util.Locale;

/**
 * SlashBlade on the models. Upstream, with a SlashBlade in the main hand: the {@code :slashblade} class for the
 * hold, swing and use names ({@code hold_mainhand:slashblade}); the main state retried as {@code slashblade:<state>}
 * ({@code slashblade:idle}, {@code slashblade:walk}...) when the model (or the default model) has it; and on the swing
 * controller the blade's current combo state ({@code slashblade:combo_a1}...) played once, which also keeps the plain
 * swing from playing while a combo runs. Models show {@code BladeLocator} and {@code SheathLocator} only inside those
 * clips.
 * <p>
 * On 1.7.10 a mod that draws such blades says so with two external states ({@code FuturaModels.setExternalState}):
 * {@link #HELD} while the main hand holds a blade, and {@link #COMBO} with the combo state name while one runs. An
 * {@code ItemSlashBlade} (the SlashBlade mod itself) also counts as held, through its item class.
 */
public final class SlashBladeStates {

    /** Any value: the main hand holds a SlashBlade-style blade. */
    public static final String HELD = "slashblade.held";
    /**
     * The combo state playing ({@code combo_a1} or {@code slashblade:combo_a1}); unset or blank between combos. Set
     * to null and back to restart the same combo.
     */
    public static final String COMBO = "slashblade.combo";
    /** The item class ({@code :slashblade}) and the animation namespace. */
    public static final String CLASSIFY = "slashblade";
    public static final String PREFIX = "slashblade:";

    private SlashBladeStates() {}

    /** Whether the player holds a blade in the main hand. */
    public static boolean holds(PlayerInputs in) {
        return CLASSIFY.equals(in.mainhand().classify) || in.externalState(HELD) != null;
    }

    /**
     * The animation of a combo state: {@code combo_a1} and {@code slashblade:combo_a1} are {@code slashblade:combo_a1};
     * another namespace is kept ({@code slashblade_addon:fire_spiral}); upstream's name fix
     * ({@code combo_a4_ex} is {@code combo_a4ex}).
     *
     * @return null for null or blank
     */
    public static String comboAnimation(String state) {
        if (state == null) {
            return null;
        }
        String s = state.trim()
            .toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        if (s.indexOf(':') < 0) {
            s = PREFIX + s;
        }
        if (s.equals(PREFIX + "combo_a4_ex")) {
            s = PREFIX + "combo_a4ex";
        }
        return s;
    }

    /** The combo animation the player's inputs ask for now, or null. */
    public static String combo(PlayerInputs in) {
        return comboAnimation(in.externalState(COMBO));
    }

    /** Upstream {@code playMainAnimation}: {@code slashblade:<state>} when the library has it, else the state. */
    public static String mainState(String state, AnimationLibrary library) {
        String name = PREFIX + state;
        return library.get(name) != null ? name : state;
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/animation/AnimationRegister.java,
 * src/main/java/com/elfmcys/ysm/client/animation/predicate/PlayerMainPredicate.java and selectedControl() of
 * src/main/java/com/elfmcys/ysm/expression/host/builtin/ControlReadBuiltins.java, Apache License 2.0.
 * Changes: the priority table is a fixed if-chain over {@link PlayerInputs}; mod compat states (ParCool, Create,
 * SlashBlade, TaCZ, Superb Warfare) are not ported.
 */
package com.akashic.futura.anim;

import com.akashic.futura.format.pojo.animation.LoopType;

/**
 * The {@code main} controller's state table: the first state whose condition holds wins (highest priority first).
 * {@code death} and {@code attacked} play once, every other state loops.
 */
public final class MainStates {

    public static final String IDLE = "idle";
    private static final float MIN_SPEED = 0.05f;

    public static final String[] NAMES = { "death", "riptide", "sleep", "swim", "climb", "climbing", "ladder_up",
        "ladder_stillness", "ladder_down", "fly", "elytra_fly", "swim_stand", "attacked", "jump", "sneak", "sneaking",
        "run", "walk", IDLE };

    private MainStates() {}

    /** Loop type the {@code main} controller forces for a state. */
    public static LoopType loop(String state) {
        return "death".equals(state) || "attacked".equals(state) ? LoopType.PLAY_ONCE : LoopType.LOOP;
    }

    /**
     * Selects the main state.
     *
     * @param in              the player
     * @param limbSwingAmount the frame's limb swing amount (0 while sitting in a vehicle)
     * @param absoluteSpeed   {@code ctrl.*} compares {@code |limbSwingAmount|}; the main predicate's {@code walk}
     *                        compares the signed value
     */
    public static String select(PlayerInputs in, float limbSwingAmount, boolean absoluteSpeed) {
        boolean moving = Math.abs(limbSwingAmount) > MIN_SPEED;
        float verticalSpeed = 20 * (float) (in.y() - in.prevY());
        if (in.isDeadOrDying()) {
            return "death";
        }
        if (in.isAutoSpinAttack()) {
            return "riptide";
        }
        if (in.isSleeping()) {
            return "sleep";
        }
        if (in.isSwimming()) {
            return "swim";
        }
        if (in.isCrawling() && moving) {
            return "climb";
        }
        if (in.isCrawling()) {
            return "climbing";
        }
        if (in.onLadder() && verticalSpeed > 0) {
            return "ladder_up";
        }
        if (in.onLadder() && verticalSpeed == 0) {
            return "ladder_stillness";
        }
        if (in.onLadder()) {
            return "ladder_down";
        }
        if (in.isFlying()) {
            return "fly";
        }
        if (in.isFallFlying()) {
            return "elytra_fly";
        }
        if (in.isInWater() && !in.onGround()) {
            return "swim_stand";
        }
        if (in.hurtTime() > 0) {
            return "attacked";
        }
        if (!in.onGround() && !in.isInWater()) {
            return "jump";
        }
        if (in.onGround() && in.isSneaking() && moving) {
            return "sneak";
        }
        if (in.onGround() && in.isSneaking()) {
            return "sneaking";
        }
        if (in.onGround() && in.isSprinting()) {
            return "run";
        }
        if (in.onGround() && (absoluteSpeed ? moving : limbSwingAmount > MIN_SPEED)) {
            return "walk";
        }
        return IDLE;
    }
}

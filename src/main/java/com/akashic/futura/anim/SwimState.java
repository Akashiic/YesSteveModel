package com.akashic.futura.anim;

/**
 * The 1.13+ swimming pose on 1.7.10, which has none: sprinting with the eyes under water starts it, and it lasts while
 * the player sprints in water (as {@code Player.updateSwimming} decides it, and as Akashic Player Backport's
 * {@code SwimmingDecision} does). Drives the YSM {@code swim} state when no backport mod says better. One per player;
 * no Minecraft classes.
 */
public final class SwimState {

    private boolean swimming;

    /**
     * @param eyesInWater the eyes are under water
     * @param inWater     the body touches water
     * @return whether he swims now
     */
    public boolean update(boolean sprinting, boolean inWater, boolean eyesInWater, boolean riding, boolean flying) {
        if (flying || riding || !sprinting) {
            swimming = false;
        } else if (swimming) {
            swimming = inWater;
        } else {
            swimming = eyesInWater && inWater;
        }
        return swimming;
    }

    public boolean swimming() {
        return swimming;
    }
}

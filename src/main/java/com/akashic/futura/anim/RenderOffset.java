package com.akashic.futura.anim;

/**
 * Where a player's model stands relative to his feet, as upstream (1.20) places it: at the feet, and 0.125 blocks lower
 * while crouching ({@code PlayerRenderer.getRenderOffset}, applied by the render dispatcher before the model is drawn).
 * The models' crouch animations are made for that offset. No Minecraft classes.
 * <p>
 * 1.20's crouching is the {@code CROUCHING} pose: shift held, not flying, not gliding, swimming, sleeping or riding (a
 * passenger stands; shift dismounts).
 */
public final class RenderOffset {

    /** {@code PlayerRenderer.getRenderOffset} of a crouching player, in blocks. */
    public static final float CROUCH = -0.125f;

    private RenderOffset() {}

    /** Whether the player is in 1.20's crouching pose. */
    public static boolean crouching(PlayerInputs in) {
        return in.isSneaking() && !in.isFlying()
            && !in.isFallFlying()
            && !in.isSwimming()
            && !in.isCrawling()
            && !in.isSleeping()
            && !in.isRiding()
            && !in.isDeadOrDying();
    }

    /** The model's height below the player's feet, in blocks (0 or {@link #CROUCH}). */
    public static float of(PlayerInputs in) {
        return crouching(in) ? CROUCH : 0;
    }
}

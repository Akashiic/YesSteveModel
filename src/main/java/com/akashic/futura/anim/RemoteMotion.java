package com.akashic.futura.anim;

/**
 * What a 1.7.10 client never learns about other players, estimated from their positions. Their entities
 * ({@code EntityOtherPlayerMP}) are moved by interpolation without collisions, so {@code onGround} stays false,
 * {@code motionX/Z} keep whatever a velocity packet last set, {@code distanceWalkedModified} never grows and the
 * {@code isFlying} ability is not sent. Left alone, every other player's model would hang in the {@code jump} state and
 * slide around instead of walking. Updated once per entity tick; no Minecraft classes.
 */
public final class RemoteMotion {

    /** Ticks a player keeps {@code onGround} after the ground under him is gone, unless he moves up. */
    static final int GROUND_GRACE_TICKS = 3;
    /** Ticks in the air without moving up or down before he counts as flying. */
    static final int HOVER_TICKS = 8;
    /** Vertical movement (blocks per tick) under which he hovers. */
    static final double HOVER_EPSILON = 0.005;
    /** Falling faster than this (blocks per tick) ends flying. */
    static final double FALLING = -0.45;

    private int tick = Integer.MIN_VALUE;
    private boolean onGround = true;
    private boolean flying;
    private int airTicks;
    private int hoverTicks;
    private double dx, dz;
    private float walked, walkedOnStep;

    /** Whether {@link #update} has not seen this tick yet (the ground probe is not free: skip it otherwise). */
    public boolean stale(int tick) {
        return tick != this.tick;
    }

    /**
     * One entity tick (repeated calls in the same tick are ignored).
     *
     * @param tick        the entity's {@code ticksExisted}
     * @param dx          {@code posX - prevPosX}: the tick's movement
     * @param groundBelow a collision box right under his feet
     * @param riding      in a vehicle (no walking)
     */
    public void update(int tick, double dx, double dy, double dz, boolean groundBelow, boolean inWater,
        boolean riding) {
        if (tick == this.tick) {
            return;
        }
        this.tick = tick;
        this.dx = dx;
        this.dz = dz;
        if (groundBelow) {
            airTicks = 0;
            onGround = true;
        } else {
            airTicks++;
            // walking down stairs, the interpolated feet are briefly above the next step
            onGround = airTicks <= GROUND_GRACE_TICKS && dy <= 0 && dy > FALLING && onGround;
        }
        hoverTicks = !groundBelow && !inWater && Math.abs(dy) < HOVER_EPSILON ? hoverTicks + 1 : 0;
        if (groundBelow || inWater || riding || dy < FALLING) {
            flying = false;
        } else if (hoverTicks >= HOVER_TICKS) {
            flying = true;
        }
        if (!riding) {
            walked += (float) (Math.sqrt(dx * dx + dz * dz) * 0.6);
            walkedOnStep += (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.6);
        }
    }

    public boolean onGround() {
        return onGround;
    }

    public boolean flying() {
        return flying;
    }

    /** The tick's movement as {@code motionX} ({@code query.ground_speed} reads it). */
    public double motionX() {
        return dx;
    }

    public double motionZ() {
        return dz;
    }

    /** {@code distanceWalkedModified} as {@code Entity.moveEntity} would have grown it. */
    public float walked() {
        return walked;
    }

    public float walkedOnStep() {
        return walkedOnStep;
    }
}

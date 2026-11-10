package com.akashic.futura.anim;

/**
 * What the animation of a projectile or vehicle reads from its entity each frame, as plain fields (the client fills it
 * from the 1.7.10 entity; tests set it). Angles are degrees, positions blocks. Defaults describe an entity at rest on
 * the ground.
 */
public final class EntitySnapshot {

    /** {@code entity.ticksExisted}. */
    public int ticksExisted;
    /** The frame's partial tick. */
    public float partialTicks;

    /** {@code posX/Y/Z} this tick and {@code prevPosX/Y/Z}; y is the bottom of the bounding box. */
    public double x, y, z, prevX, prevY, prevZ;
    /** {@code motionX/Y/Z} (blocks per tick). */
    public double motionX, motionY, motionZ;
    /** Interpolated {@code rotationYaw} / {@code rotationPitch}, and the yaw change of the last tick. */
    public float yaw, pitch, yawSpeed;
    /** The body yaw of a living vehicle ({@code renderYawOffset}), else the yaw the model is drawn with. */
    public float bodyYaw;

    public boolean onGround = true;
    /** {@code isInWater()}. */
    public boolean inWater;
    /** {@code isBurning()}. */
    public boolean onFire;
    /** An arrow stuck in a block ({@code EntityArrow.inGround}). */
    public boolean inGround;
    /** Something rides it ({@code riddenByEntity != null}). */
    public boolean hasRider;

    /** A living vehicle (pig, horse): health and forward input; 0 otherwise. */
    public boolean living;
    public float health, maxHealth;
    /** {@code moveForward} of a living vehicle. */
    public float moveForward;

    /** The item the shooter held when the arrow was first seen ({@code minecraft:bow}), or "". */
    public String shootItemId = "";

    public long worldTime;
    /** 0 clear, 1 rain, 2 thunder. */
    public int weather;
    public boolean openAir = true;
    public int fps = 60;
}

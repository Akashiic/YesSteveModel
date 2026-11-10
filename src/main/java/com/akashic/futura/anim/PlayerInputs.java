package com.akashic.futura.anim;

import java.util.Collection;
import java.util.Collections;

/**
 * Everything the animation runtime reads from a player each frame, without Minecraft classes. The client implements
 * it over {@code EntityPlayer} (or fills a {@link PlayerSnapshot}). Each getter says where the value comes from on
 * 1.7.10; angles are degrees, positions blocks. Features 1.7.10 lacks have default methods returning false/0/empty, so
 * a client with a mod that adds them can still provide them.
 */
public interface PlayerInputs {

    // ---- time and identity ----

    /** {@code entity.ticksExisted}. Entity-tick bookkeeping (swing/use restart once per tick) keys on it. */
    int ticksExisted();

    /** The frame's partial tick ({@code renderPartialTicks} / the {@code partialTicks} the renderer gets). */
    float partialTicks();

    /** {@code getUniqueID().getLeastSignificantBits()}: offsets the blink ({@code ysm.is_close_eyes}). */
    long uuidLeastSignificantBits();

    // ---- position, rotation, movement ----

    /** {@code posX}, this tick. */
    double x();

    /** {@code posY}, this tick (feet). */
    double y();

    /** {@code posZ}, this tick. */
    double z();

    /** {@code prevPosX} (start of this tick). */
    double prevX();

    /** {@code prevPosY}. Ladder states use {@code 20·(y − prevY)}. */
    double prevY();

    /** {@code prevPosZ}. */
    double prevZ();

    /** {@code motionX}: {@code query.ground_speed} is {@code 20·√(motionX² + motionZ²)}. */
    double motionX();

    /** {@code motionZ}. */
    double motionZ();

    /**
     * Interpolated limb swing, as {@code RendererLivingEntity} computes it:
     * {@code limbSwing − limbSwingAmount·(1 − partialTicks)}.
     */
    float limbSwing();

    /**
     * Interpolated limb swing amount: {@code prevLimbSwingAmount + (limbSwingAmount − prevLimbSwingAmount)·pt}. Walk
     * and sneak states need it above 0.05.
     */
    float limbSwingAmount();

    /** Interpolated body yaw: {@code interpolateRotation(prevRenderYawOffset, renderYawOffset, pt)}. */
    float bodyYaw();

    /** Interpolated head yaw: {@code interpolateRotation(prevRotationYawHead, rotationYawHead, pt)}. */
    float headYaw();

    /** Interpolated view yaw: {@code prevRotationYaw + (rotationYaw − prevRotationYaw)·pt}. */
    float yaw();

    /** Interpolated pitch: {@code prevRotationPitch + (rotationPitch − prevRotationPitch)·pt}; positive looks down. */
    float pitch();

    /**
     * {@code query.yaw_speed}, degrees per second: {@code 20·(rotationYaw − prevRotationYaw)} (upstream samples the
     * local player per tick instead).
     */
    float yawSpeed();

    /** {@code moveForward} (forward input, {@code ysm.yya}). */
    float moveForward();

    // ---- state ----

    /** {@code getHealth() <= 0} ({@code isDeadOrDying}). */
    boolean isDeadOrDying();

    /** {@code isPlayerSleeping()}. */
    boolean isSleeping();

    /** {@code onGround}. */
    boolean onGround();

    /** {@code isInWater()}. */
    boolean isInWater();

    /** {@code isWet()} (water or rain). */
    default boolean isInWaterOrRain() {
        return isInWater();
    }

    /** {@code isOnLadder()}. */
    boolean onLadder();

    /** {@code isSneaking()}. */
    boolean isSneaking();

    /** {@code isSprinting()}. */
    boolean isSprinting();

    /** {@code capabilities.isFlying} (for other players, whatever the client syncs). */
    boolean isFlying();

    /** {@code isBurning()}. */
    boolean isOnFire();

    /** {@code hurtTime}: the {@code attacked} state plays while it is above 0. */
    int hurtTime();

    /** {@code getHealth()}. */
    float health();

    /** {@code getMaxHealth()}. */
    float maxHealth();

    /** {@code getFoodStats().getFoodLevel()}. */
    int foodLevel();

    /** {@code getTotalArmorValue()}. */
    int armorValue();

    /** {@code experienceLevel}. */
    int experienceLevel();

    /** {@code distanceWalkedModified} ({@code query.modified_distance_moved}). */
    default float distanceWalkedModified() {
        return 0;
    }

    /** {@code distanceWalkedOnStepModified} ({@code query.walk_distance}). */
    default float distanceWalkedOnStepModified() {
        return 0;
    }

    /** 1.13+ swimming pose: no such thing on 1.7.10. */
    default boolean isSwimming() {
        return false;
    }

    /** 1.14+ crawling ({@code Pose.SWIMMING} out of water): not on 1.7.10. */
    default boolean isCrawling() {
        return false;
    }

    /** Elytra flight: not on 1.7.10. */
    default boolean isFallFlying() {
        return false;
    }

    /** Riptide spin: not on 1.7.10. */
    default boolean isAutoSpinAttack() {
        return false;
    }

    // ---- items ----

    /** {@code getHeldItem()} as an {@link ItemInfo}; {@link ItemInfo#EMPTY} for none. */
    ItemInfo mainhand();

    /** No offhand on 1.7.10 (a Backhand-style mod could provide it). */
    default ItemInfo offhand() {
        return ItemInfo.EMPTY;
    }

    /**
     * Armour in a slot: {@code getCurrentArmor(0..3)} is feet, legs, chest, head
     * ({@code inventory.armorInventory}).
     */
    ItemInfo armor(ItemConditions.Slot slot);

    /** {@code isSwingInProgress}. */
    boolean isSwinging();

    /** {@code swingProgressInt}: 0 on the first tick of a swing. */
    int swingTime();

    /** Whether the swinging arm is the offhand: never on 1.7.10. */
    default boolean isSwingingOffhand() {
        return false;
    }

    /** {@code isUsingItem()}. */
    boolean isUsingItem();

    /** Whether the used item is in the offhand: never on 1.7.10. */
    default boolean isUsingOffhand() {
        return false;
    }

    /** {@code getItemInUseDuration()} (ticks since use began; 1 on the first tick). */
    int ticksUsingItem();

    /** {@code getItemInUseCount()} (ticks left). */
    int itemUseRemainingTicks();

    /** {@code getItemInUse()} as an {@link ItemInfo} (the mainhand stack on 1.7.10). */
    default ItemInfo usedItem() {
        return isUsingItem() ? mainhand() : ItemInfo.EMPTY;
    }

    /** {@code fishEntity != null}. */
    boolean isFishing();

    /** A charged crossbow in a hand: never on 1.7.10. */
    default boolean hasChargedCrossbow(boolean offhand) {
        return false;
    }

    // ---- vehicle and passenger ----

    /** {@code ridingEntity != null && !ridingEntity.isDead}. */
    boolean isRiding();

    /**
     * Vehicle entity id with namespace, matched by {@code vehicle$<id>}: modern names for vanilla ones
     * ({@code minecraft:pig}, {@code minecraft:horse}, {@code minecraft:boat}, {@code minecraft:minecart}), else
     * {@code EntityList.getEntityString} lower-cased with the mod id. null when not riding.
     */
    String vehicleId();

    /** Entity type tags of the vehicle for {@code vehicle#<tag>}; none on 1.7.10 unless the client emulates them. */
    default Collection<String> vehicleTags() {
        return Collections.emptyList();
    }

    /** What the vehicle is, for the legacy fallbacks {@code ride_pig}, {@code ride} and {@code boat}. */
    VehicleKind vehicleKind();

    /** {@code ridingEntity.shouldRiderSit()} (Forge): a sitting rider has no limb swing. */
    boolean riderSits();

    /** Whether the vehicle is an {@code EntityLivingBase} (its body yaw then clamps the rider's head). */
    boolean vehicleIsLiving();

    /** Interpolated {@code renderYawOffset} of a living vehicle. */
    float vehicleBodyYaw();

    /** Id of {@code riddenByEntity} (namespaced as {@link #vehicleId()}), or null. */
    String passengerId();

    /** Entity type tags of the passenger. */
    default Collection<String> passengerTags() {
        return Collections.emptyList();
    }

    // ---- world and view ----

    /** 2 thundering, 1 raining, 0 clear: {@code worldObj.isThundering()} / {@code isRaining()}. */
    int weather();

    /** {@code worldObj.getWorldTime()}. */
    long worldTime();

    /** {@code worldObj.getMoonPhase()}. */
    int moonPhase();

    /** {@code canBlockSeeTheSky(x, y, z)} and the precipitation height not above the feet. */
    boolean isOpenAir();

    /** Local player in first person ({@code gameSettings.thirdPersonView == 0}). */
    default boolean isFirstPerson() {
        return false;
    }

    /** Being drawn in an inventory or GUI preview. */
    default boolean isRenderingInInventory() {
        return false;
    }

    /** Distance from the camera ({@code RenderManager} view entity). */
    default float distanceFromCamera() {
        return 0;
    }

    /** {@code Minecraft.debugFPS}. */
    default int fps() {
        return 60;
    }

    /** The texture in use (file stem), for {@code ysm.texture_name}. */
    default String textureName() {
        return "";
    }

    /** {@code AbstractClientPlayer.func_152122_n()} (cape loaded) and not invisible. */
    default boolean hasCape() {
        return false;
    }

    /**
     * A state another mod set on the player ({@code FuturaModels.setExternalState}, see {@link ExternalStates}), e.g.
     * {@link SlashBladeStates#COMBO}; null when unset.
     */
    default String externalState(String key) {
        return null;
    }

    /** Changes whenever {@link #externalState} of the key changes (0 before the first change). */
    default int externalGeneration(String key) {
        return 0;
    }

    /** What a vehicle is, for the legacy fallbacks. */
    enum VehicleKind {
        NONE,
        /** {@code EntityPig}: {@code ride_pig} */
        PIG,
        /** saddled mounts ({@code EntityHorse}): {@code ride} */
        SADDLEABLE,
        /** {@code EntityBoat}: {@code boat} */
        BOAT,
        /** anything else (minecarts, modded): {@code sit} */
        OTHER
    }
}

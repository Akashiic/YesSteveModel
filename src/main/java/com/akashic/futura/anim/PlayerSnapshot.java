package com.akashic.futura.anim;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * A {@link PlayerInputs} made of plain fields: fill it once per frame (see each getter of {@link PlayerInputs} for the
 * 1.7.10 source) and hand it to {@link PlayerAnimator#update}. Defaults describe a living player standing still.
 */
public final class PlayerSnapshot implements PlayerInputs {

    public int ticksExisted;
    public float partialTicks;
    public long uuidLeastSignificantBits;

    public double x, y, z, prevX, prevY, prevZ;
    public double motionX, motionZ;
    public float limbSwing, limbSwingAmount;
    public float bodyYaw, headYaw, yaw, pitch, yawSpeed, moveForward;

    public boolean deadOrDying, sleeping, onGround = true, inWater, inWaterOrRain, onLadder, sneaking, sprinting,
        flying, onFire;
    public boolean swimming, crawling, fallFlying, autoSpinAttack;
    public int hurtTime;
    public float health = 20, maxHealth = 20;
    public int foodLevel = 20, armorValue, experienceLevel;
    public float distanceWalkedModified, distanceWalkedOnStepModified;

    public ItemInfo mainhand = ItemInfo.EMPTY;
    public ItemInfo offhand = ItemInfo.EMPTY;
    public final Map<ItemConditions.Slot, ItemInfo> armor = new EnumMap<>(ItemConditions.Slot.class);
    public boolean swinging, swingingOffhand;
    public int swingTime;
    public boolean usingItem, usingOffhand;
    public int ticksUsingItem, itemUseRemainingTicks;
    public ItemInfo usedItem;
    public boolean fishing;
    /** States other mods set on this player, or null. */
    public ExternalStates.Entry external;

    public boolean riding;
    public String vehicleId;
    public Collection<String> vehicleTags = Collections.emptyList();
    public VehicleKind vehicleKind = VehicleKind.NONE;
    public boolean riderSits = true;
    public boolean vehicleIsLiving;
    public float vehicleBodyYaw;
    public String passengerId;
    public Collection<String> passengerTags = Collections.emptyList();

    public int weather;
    public long worldTime;
    public int moonPhase;
    public boolean openAir = true;
    public boolean firstPerson, renderingInInventory, hasCape;
    public float distanceFromCamera;
    public int fps = 60;
    public String textureName = "";

    @Override
    public int ticksExisted() {
        return ticksExisted;
    }

    @Override
    public float partialTicks() {
        return partialTicks;
    }

    @Override
    public long uuidLeastSignificantBits() {
        return uuidLeastSignificantBits;
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double z() {
        return z;
    }

    @Override
    public double prevX() {
        return prevX;
    }

    @Override
    public double prevY() {
        return prevY;
    }

    @Override
    public double prevZ() {
        return prevZ;
    }

    @Override
    public double motionX() {
        return motionX;
    }

    @Override
    public double motionZ() {
        return motionZ;
    }

    @Override
    public float limbSwing() {
        return limbSwing;
    }

    @Override
    public float limbSwingAmount() {
        return limbSwingAmount;
    }

    @Override
    public float bodyYaw() {
        return bodyYaw;
    }

    @Override
    public float headYaw() {
        return headYaw;
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public float yawSpeed() {
        return yawSpeed;
    }

    @Override
    public float moveForward() {
        return moveForward;
    }

    @Override
    public boolean isDeadOrDying() {
        return deadOrDying;
    }

    @Override
    public boolean isSleeping() {
        return sleeping;
    }

    @Override
    public boolean onGround() {
        return onGround;
    }

    @Override
    public boolean isInWater() {
        return inWater;
    }

    @Override
    public boolean isInWaterOrRain() {
        return inWater || inWaterOrRain;
    }

    @Override
    public boolean onLadder() {
        return onLadder;
    }

    @Override
    public boolean isSneaking() {
        return sneaking;
    }

    @Override
    public boolean isSprinting() {
        return sprinting;
    }

    @Override
    public boolean isFlying() {
        return flying;
    }

    @Override
    public boolean isOnFire() {
        return onFire;
    }

    @Override
    public int hurtTime() {
        return hurtTime;
    }

    @Override
    public float health() {
        return health;
    }

    @Override
    public float maxHealth() {
        return maxHealth;
    }

    @Override
    public int foodLevel() {
        return foodLevel;
    }

    @Override
    public int armorValue() {
        return armorValue;
    }

    @Override
    public int experienceLevel() {
        return experienceLevel;
    }

    @Override
    public float distanceWalkedModified() {
        return distanceWalkedModified;
    }

    @Override
    public float distanceWalkedOnStepModified() {
        return distanceWalkedOnStepModified;
    }

    @Override
    public boolean isSwimming() {
        return swimming;
    }

    @Override
    public boolean isCrawling() {
        return crawling;
    }

    @Override
    public boolean isFallFlying() {
        return fallFlying;
    }

    @Override
    public boolean isAutoSpinAttack() {
        return autoSpinAttack;
    }

    @Override
    public ItemInfo mainhand() {
        return mainhand == null ? ItemInfo.EMPTY : mainhand;
    }

    @Override
    public ItemInfo offhand() {
        return offhand == null ? ItemInfo.EMPTY : offhand;
    }

    @Override
    public ItemInfo armor(ItemConditions.Slot slot) {
        ItemInfo item = armor.get(slot);
        return item == null ? ItemInfo.EMPTY : item;
    }

    @Override
    public boolean isSwinging() {
        return swinging;
    }

    @Override
    public int swingTime() {
        return swingTime;
    }

    @Override
    public boolean isSwingingOffhand() {
        return swingingOffhand;
    }

    @Override
    public boolean isUsingItem() {
        return usingItem;
    }

    @Override
    public boolean isUsingOffhand() {
        return usingOffhand;
    }

    @Override
    public int ticksUsingItem() {
        return ticksUsingItem;
    }

    @Override
    public int itemUseRemainingTicks() {
        return itemUseRemainingTicks;
    }

    @Override
    public ItemInfo usedItem() {
        if (!usingItem) {
            return ItemInfo.EMPTY;
        }
        if (usedItem != null) {
            return usedItem;
        }
        return usingOffhand ? offhand() : mainhand();
    }

    @Override
    public boolean isFishing() {
        return fishing;
    }

    @Override
    public boolean isRiding() {
        return riding;
    }

    @Override
    public String vehicleId() {
        return riding ? vehicleId : null;
    }

    @Override
    public Collection<String> vehicleTags() {
        return vehicleTags;
    }

    @Override
    public VehicleKind vehicleKind() {
        return riding ? vehicleKind : VehicleKind.NONE;
    }

    @Override
    public boolean riderSits() {
        return riderSits;
    }

    @Override
    public boolean vehicleIsLiving() {
        return vehicleIsLiving;
    }

    @Override
    public float vehicleBodyYaw() {
        return vehicleBodyYaw;
    }

    @Override
    public String passengerId() {
        return passengerId;
    }

    @Override
    public Collection<String> passengerTags() {
        return passengerTags;
    }

    @Override
    public int weather() {
        return weather;
    }

    @Override
    public long worldTime() {
        return worldTime;
    }

    @Override
    public int moonPhase() {
        return moonPhase;
    }

    @Override
    public boolean isOpenAir() {
        return openAir;
    }

    @Override
    public boolean isFirstPerson() {
        return firstPerson;
    }

    @Override
    public boolean isRenderingInInventory() {
        return renderingInInventory;
    }

    @Override
    public float distanceFromCamera() {
        return distanceFromCamera;
    }

    @Override
    public int fps() {
        return fps;
    }

    @Override
    public String textureName() {
        return textureName;
    }

    @Override
    public boolean hasCape() {
        return hasCape;
    }

    @Override
    public String externalState(String key) {
        return external == null ? null : external.get(key);
    }

    @Override
    public int externalGeneration(String key) {
        return external == null ? 0 : external.generation(key);
    }
}

package com.akashic.futura.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

import com.akashic.futura.anim.ItemConditions;
import com.akashic.futura.anim.ItemInfo;
import com.akashic.futura.anim.PlayerInputs;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.anim.SlashBladeStates;

/** Fills a {@link PlayerSnapshot} from a 1.7.10 player, once per frame (render thread). */
final class PlayerInputsAdapter {

    /** Item info by item and damage: the OreDictionary lookup is not free. */
    private static final Map<Long, ItemInfo> ITEMS = new HashMap<>();

    private PlayerInputsAdapter() {}

    static void fill(PlayerSnapshot s, AbstractClientPlayer p, float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        s.ticksExisted = p.ticksExisted;
        s.partialTicks = pt;
        s.uuidLeastSignificantBits = p.getUniqueID()
            .getLeastSignificantBits();

        s.x = p.posX;
        s.y = p.posY;
        s.z = p.posZ;
        s.prevX = p.prevPosX;
        s.prevY = p.prevPosY;
        s.prevZ = p.prevPosZ;
        s.motionX = p.motionX;
        s.motionZ = p.motionZ;
        s.limbSwing = p.limbSwing - p.limbSwingAmount * (1 - pt);
        s.limbSwingAmount = p.prevLimbSwingAmount + (p.limbSwingAmount - p.prevLimbSwingAmount) * pt;
        s.bodyYaw = lerpAngle(p.prevRenderYawOffset, p.renderYawOffset, pt);
        s.headYaw = lerpAngle(p.prevRotationYawHead, p.rotationYawHead, pt);
        s.yaw = lerpAngle(p.prevRotationYaw, p.rotationYaw, pt);
        s.pitch = p.prevRotationPitch + (p.rotationPitch - p.prevRotationPitch) * pt;
        s.yawSpeed = 20 * MathHelper.wrapAngleTo180_float(p.rotationYaw - p.prevRotationYaw);
        s.moveForward = p.moveForward;

        s.deadOrDying = p.getHealth() <= 0;
        s.sleeping = p.isPlayerSleeping();
        s.onGround = p.onGround;
        s.inWater = p.isInWater();
        s.inWaterOrRain = p.isWet();
        s.onLadder = p.isOnLadder();
        s.sneaking = p.isSneaking();
        s.sprinting = p.isSprinting();
        s.flying = p.capabilities.isFlying;
        s.onFire = p.isBurning();
        s.hurtTime = p.hurtTime;
        s.health = p.getHealth();
        s.maxHealth = p.getMaxHealth();
        s.foodLevel = p.getFoodStats()
            .getFoodLevel();
        s.armorValue = p.getTotalArmorValue();
        s.experienceLevel = p.experienceLevel;
        s.distanceWalkedModified = p.distanceWalkedModified;
        s.distanceWalkedOnStepModified = p.distanceWalkedOnStepModified;

        s.mainhand = item(p.getHeldItem());
        s.external = PlayerModels.INSTANCE.externalStates()
            .of(p.getUniqueID());
        if (s.external != null && s.external.get(SlashBladeStates.HELD) != null
            && !SlashBladeStates.CLASSIFY.equals(s.mainhand.classify)) {
            s.mainhand = asSlashBlade(s.mainhand); // a mod says it is a blade: hold_mainhand:slashblade...
        }
        s.armor.put(ItemConditions.Slot.FEET, item(p.getCurrentArmor(0)));
        s.armor.put(ItemConditions.Slot.LEGS, item(p.getCurrentArmor(1)));
        s.armor.put(ItemConditions.Slot.CHEST, item(p.getCurrentArmor(2)));
        s.armor.put(ItemConditions.Slot.HEAD, item(p.getCurrentArmor(3)));
        s.swinging = p.isSwingInProgress;
        s.swingTime = p.swingProgressInt;
        s.usingItem = p.isUsingItem();
        s.ticksUsingItem = p.getItemInUseDuration();
        s.itemUseRemainingTicks = p.getItemInUseCount();
        s.usedItem = p.isUsingItem() ? item(p.getItemInUse()) : null;
        s.fishing = p.fishEntity != null;

        Entity vehicle = p.ridingEntity;
        s.riding = vehicle != null && !vehicle.isDead;
        s.vehicleId = vehicle == null ? null : entityId(vehicle);
        s.vehicleKind = vehicle == null ? PlayerInputs.VehicleKind.NONE
            : vehicle instanceof EntityPig ? PlayerInputs.VehicleKind.PIG
                : vehicle instanceof EntityHorse ? PlayerInputs.VehicleKind.SADDLEABLE
                    : vehicle instanceof EntityBoat ? PlayerInputs.VehicleKind.BOAT : PlayerInputs.VehicleKind.OTHER;
        s.riderSits = vehicle == null || vehicle.shouldRiderSit();
        s.vehicleIsLiving = vehicle instanceof EntityLivingBase;
        if (s.vehicleIsLiving) {
            EntityLivingBase living = (EntityLivingBase) vehicle;
            s.vehicleBodyYaw = lerpAngle(living.prevRenderYawOffset, living.renderYawOffset, pt);
        }
        s.passengerId = p.riddenByEntity == null ? null : entityId(p.riddenByEntity);

        World w = p.worldObj;
        s.weather = w.isThundering() ? 2 : w.isRaining() ? 1 : 0;
        s.worldTime = w.getWorldTime();
        s.moonPhase = w.provider.getMoonPhase(w.getWorldTime());
        s.openAir = w.canBlockSeeTheSky(
            MathHelper.floor_double(p.posX),
            MathHelper.floor_double(p.boundingBox.minY + 0.1),
            MathHelper.floor_double(p.posZ));
        s.firstPerson = mc.gameSettings.thirdPersonView == 0 && p == mc.renderViewEntity;
        // GuiInventory draws the player with the view yaw forced to 180.
        s.renderingInInventory = RenderManager.instance.playerViewY == 180f && mc.currentScreen != null;
        s.distanceFromCamera = mc.renderViewEntity == null ? 0 : p.getDistanceToEntity(mc.renderViewEntity);
    }

    /** The same item with the {@code slashblade} class, cached per item info. */
    private static final Map<ItemInfo, ItemInfo> BLADES = new java.util.IdentityHashMap<>();

    static ItemInfo asSlashBlade(ItemInfo base) {
        ItemInfo blade = BLADES.get(base);
        if (blade == null) {
            blade = new ItemInfo(
                base.id,
                base.tags,
                SlashBladeStates.CLASSIFY,
                base.useAction,
                base.maxUseDuration,
                base.stackHash);
            if (BLADES.size() > 256) {
                BLADES.clear();
            }
            BLADES.put(base, blade);
        }
        return blade;
    }

    static ItemInfo item(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return ItemInfo.EMPTY;
        }
        Item item = stack.getItem();
        long key = ((long) Item.getIdFromItem(item) << 32) | (stack.getItemDamage() & 0xffffffffL);
        ItemInfo info = ITEMS.get(key);
        if (info == null) {
            info = build(stack, item);
            if (ITEMS.size() > 4096) {
                ITEMS.clear();
            }
            ITEMS.put(key, info);
        }
        return info;
    }

    private static ItemInfo build(ItemStack stack, Item item) {
        String id = Item.itemRegistry.getNameForObject(item);
        Set<String> tags = new LinkedHashSet<>();
        for (int ore : OreDictionary.getOreIDs(stack)) {
            tags.add(OreDictionary.getOreName(ore));
        }
        String classify = item instanceof ItemSword ? "sword"
            : item instanceof ItemAxe ? "axe"
                : item instanceof ItemPickaxe ? "pickaxe"
                    : item instanceof ItemSpade ? "shovel"
                        : item instanceof ItemHoe ? "hoe"
                            : item instanceof ItemBow ? "bow"
                                : item instanceof ItemFishingRod ? "fishing_rod"
                                    : item instanceof ItemPotion && ItemPotion.isSplash(stack.getItemDamage())
                                        ? "throwable_potion"
                                        : item.getClass()
                                            .getName()
                                            .endsWith("ItemSlashBlade") ? "slashblade" : "";
        ItemInfo.UseAction action;
        EnumAction use = item.getItemUseAction(stack);
        if (use == EnumAction.eat) {
            action = ItemInfo.UseAction.EAT;
        } else if (use == EnumAction.drink) {
            action = ItemInfo.UseAction.DRINK;
        } else if (use == EnumAction.block) {
            action = ItemInfo.UseAction.BLOCK;
        } else if (use == EnumAction.bow) {
            action = ItemInfo.UseAction.BOW;
        } else {
            action = ItemInfo.UseAction.NONE;
        }
        return new ItemInfo(
            id == null ? "" : id,
            tags.isEmpty() ? Collections.<String>emptySet() : tags,
            classify,
            action,
            item.getMaxItemUseDuration(stack),
            Item.getIdFromItem(item) * 31 + stack.getItemDamage());
    }

    /**
     * Modern-style entity ids: vanilla 1.7.10 names mapped to {@code minecraft:*}, modded {@code Mod.Name} to
     * {@code mod:name}.
     */
    private static String entityId(Entity entity) {
        String name = EntityList.getEntityString(entity);
        if (name == null) {
            return "";
        }
        switch (name) {
            case "EntityHorse":
                return "minecraft:horse";
            case "MinecartRideable":
                return "minecraft:minecart";
            case "PigZombie":
                return "minecraft:zombified_piglin";
            default:
                break;
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            return name.substring(0, dot)
                .toLowerCase() + ":"
                + snake(name.substring(dot + 1));
        }
        return "minecraft:" + snake(name);
    }

    private static String snake(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(name.charAt(i - 1))) {
                b.append('_');
            }
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    private static float lerpAngle(float prev, float now, float pt) {
        return prev + MathHelper.wrapAngleTo180_float(now - prev) * pt;
    }
}

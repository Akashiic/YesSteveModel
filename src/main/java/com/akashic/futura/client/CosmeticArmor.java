package com.akashic.futura.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.akashic.futura.Futura3D;

import cpw.mods.fml.common.Loader;

/**
 * The armor a player shows, with Cosmetic Armor Reworked (1.7.10, {@code lain.mods.cos}) by reflection: a cosmetic
 * item in a slot is shown instead of the worn one, and a slot set to "show skin" shows nothing; with the mod's client
 * toggle ({@code PlayerRenderHandler.HideCosArmor}) on, the worn armor. As upstream's {@code CosmeticArmorCompat},
 * for the armor drawn on the model and its {@code armor} conditions.
 * <p>
 * The mod swaps the worn armor for the cosmetic one itself while a player is drawn ({@code RenderPlayerEvent.Pre} to
 * {@code Post}); this gives the same answer then (applying the cosmetic slots twice changes nothing) and also outside
 * that event.
 */
public final class CosmeticArmor {

    private static final String MOD = "cosmeticarmorreworked";

    private static boolean resolved;
    private static Object manager;
    private static Method inventory;
    private static Method skinArmor;
    private static Field hidden;

    private CosmeticArmor() {}

    /**
     * The item a player shows in an armor slot ({@code armorItemInSlot} numbering: 0 the boots to 3 the helmet), or
     * null.
     */
    public static ItemStack shown(EntityPlayer player, int slot) {
        ItemStack worn = player.inventory.armorItemInSlot(slot);
        if (!resolve()) {
            return worn;
        }
        try {
            if (hidden != null && hidden.getBoolean(null)) {
                return worn;
            }
            Object inv = inventory.invoke(manager, player.getUniqueID());
            if (!(inv instanceof IInventory)) {
                return worn;
            }
            if ((Boolean) skinArmor.invoke(inv, slot)) {
                return null;
            }
            ItemStack cosmetic = ((IInventory) inv).getStackInSlot(slot);
            return cosmetic != null ? cosmetic : worn;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Futura3D.LOG.warn("Cosmetic Armor Reworked stopped answering; the model shows the worn armor", e);
            manager = null;
            return worn;
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            if (Loader.isModLoaded(MOD)) {
                try {
                    Class<?> mod = Class.forName("lain.mods.cos.CosmeticArmorReworked");
                    manager = mod.getField("invMan")
                        .get(null);
                    inventory = manager.getClass()
                        .getMethod("getCosArmorInventoryClient", UUID.class);
                    skinArmor = inventory.getReturnType()
                        .getMethod("isSkinArmor", int.class);
                    try {
                        hidden = Class.forName("lain.mods.cos.client.PlayerRenderHandler")
                            .getField("HideCosArmor");
                    } catch (ReflectiveOperationException | LinkageError e) {
                        hidden = null;
                    }
                    Futura3D.LOG.info("Cosmetic Armor Reworked: the models show the cosmetic armor");
                } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                    Futura3D.LOG.warn(
                        "Cosmetic Armor Reworked is loaded but its inventory is not there; the models show the worn armor",
                        e);
                    manager = null;
                }
            }
        }
        return manager != null;
    }
}

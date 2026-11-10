package com.akashic.futura.client;

import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.akashic.futura.Futura3D;

import cpw.mods.fml.common.Loader;

/**
 * Gives Backhand's off-hand item to the model ({@link PlayerModels#setOffhandSource}), by reflection: Backhand draws
 * the off hand in {@code RenderPlayer.renderEquippedItems}, which a modelled player never reaches, so without this the
 * off-hand item disappears from every player with a model. Found in the TEOF 5.3 test (Backhand 1.8.13).
 */
final class BackhandOffhand {

    private static final String UTILS = "xonin.backhand.api.core.BackhandUtils";

    private BackhandOffhand() {}

    static void install() {
        if (!Loader.isModLoaded("backhand")) {
            return;
        }
        Method get;
        try {
            get = Class.forName(UTILS)
                .getMethod("getOffhandItem", EntityPlayer.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            Futura3D.LOG
                .warn("Backhand is loaded but its off-hand API is not there; the model draws no off-hand item", e);
            return;
        }
        PlayerModels.setOffhandSource(player -> {
            try {
                return (ItemStack) get.invoke(null, player);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        });
        Futura3D.LOG.info("Backhand off hand drawn at LeftHandLocator");
    }
}

package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.FirstPersonArms;

/**
 * Replaces the bare first-person arm (drawn by {@code ItemRenderer.renderItemInFirstPerson} with an empty hand or a
 * map) with the model's {@code RightArm}, at the matrix vanilla has set up (bobbing, equip and swing included).
 */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerArmMixin {

    @Inject(
        method = "renderFirstPersonArm(Lnet/minecraft/entity/player/EntityPlayer;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void futura3d$drawModelArm(EntityPlayer player, CallbackInfo ci) {
        if (FirstPersonArms.INSTANCE.renderRightArm(player)) {
            ci.cancel();
        }
    }
}

package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderFish;
import net.minecraft.entity.projectile.EntityFishHook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.FishingLine;

/**
 * The fishing line of a player drawn with a model starts at the rod in the model's hand ({@link FishingLine}). Only
 * the line's {@code addVertex} calls are redirected (the bobber uses {@code addVertexWithUV}), never the
 * {@code startDrawing} call another mod may inject at to draw a line of its own.
 */
@Mixin(RenderFish.class)
public abstract class RenderFishMixin {

    @Inject(method = "doRender(Lnet/minecraft/entity/projectile/EntityFishHook;DDDFF)V", at = @At("HEAD"))
    private void futura3d$lineStart(EntityFishHook hook, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        FishingLine.begin(hook, x, y, z);
    }

    @Redirect(
        method = "doRender(Lnet/minecraft/entity/projectile/EntityFishHook;DDDFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;addVertex(DDD)V"))
    private void futura3d$lineVertex(Tessellator tess, double x, double y, double z) {
        FishingLine.vertex(tess, x, y, z);
    }
}

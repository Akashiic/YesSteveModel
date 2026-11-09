package com.akashic.futura.mixin.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.FirstPersonArms;

/**
 * First-person hand pass: gives {@link FirstPersonArms} the frame's partial tick, and draws the model's
 * {@code Background} part once the arm sway is applied and the lightmap is set for the player (where Forge 1.20 fires
 * {@code RenderHandEvent}, which upstream uses for it).
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @Inject(method = "renderItemInFirstPerson(F)V", at = @At("HEAD"))
    private void futura3d$beginFrame(float partialTicks, CallbackInfo ci) {
        FirstPersonArms.INSTANCE.beginFrame(partialTicks);
    }

    @Inject(
        method = "renderItemInFirstPerson(F)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/OpenGlHelper;setLightmapTextureCoords(IFF)V",
            ordinal = 0,
            shift = At.Shift.AFTER))
    private void futura3d$drawBackground(float partialTicks, CallbackInfo ci) {
        FirstPersonArms.INSTANCE.renderBackground(Minecraft.getMinecraft().thePlayer);
    }
}

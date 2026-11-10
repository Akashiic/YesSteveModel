package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.entity.RenderMinecart;
import net.minecraft.entity.item.EntityMinecart;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.EntityReplacements;

/** A minecart ridden by a player whose model replaces minecarts ({@code files.vehicles}) is drawn with that model. */
@Mixin(RenderMinecart.class)
public abstract class RenderMinecartMixin {

    @Inject(method = "doRender(Lnet/minecraft/entity/item/EntityMinecart;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void futura3d$replace(EntityMinecart cart, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        if (EntityReplacements.INSTANCE.renderVehicle(cart, x, y, z, yaw, partialTicks)) {
            ci.cancel();
        }
    }
}

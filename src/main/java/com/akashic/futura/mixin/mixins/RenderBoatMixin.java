package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.entity.RenderBoat;
import net.minecraft.entity.item.EntityBoat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.EntityReplacements;

/** A boat ridden by a player whose model replaces boats ({@code files.vehicles}) is drawn with that model. */
@Mixin(RenderBoat.class)
public abstract class RenderBoatMixin {

    @Inject(method = "doRender(Lnet/minecraft/entity/item/EntityBoat;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void futura3d$replace(EntityBoat boat, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        if (EntityReplacements.INSTANCE.renderVehicle(boat, x, y, z, yaw, partialTicks)) {
            ci.cancel();
        }
    }
}

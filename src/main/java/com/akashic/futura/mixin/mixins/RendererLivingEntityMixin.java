package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.EntityReplacements;

/**
 * A living vehicle (pig, horse, a mod's mount) ridden by a player whose model replaces it ({@code files.vehicles}) is
 * drawn with that model, before {@code RenderLivingEvent.Pre}; its name tag stays. Players are never vehicles here.
 */
@Mixin(RendererLivingEntity.class)
public abstract class RendererLivingEntityMixin {

    @Shadow
    protected abstract void passSpecialRender(EntityLivingBase entity, double x, double y, double z);

    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void futura3d$replace(EntityLivingBase entity, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        if (!(entity instanceof EntityPlayer)
            && EntityReplacements.INSTANCE.renderVehicle(entity, x, y, z, yaw, partialTicks)) {
            passSpecialRender(entity, x, y, z);
            ci.cancel();
        }
    }
}

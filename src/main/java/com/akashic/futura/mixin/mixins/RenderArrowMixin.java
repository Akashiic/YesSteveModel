package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.entity.RenderArrow;
import net.minecraft.entity.projectile.EntityArrow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.EntityReplacements;

/**
 * An arrow shot by a player whose model replaces arrows ({@code files.projectiles}) is drawn with that model; any other
 * arrow, or a failure, keeps the vanilla arrow. The render manager still draws the shadow and fire afterwards.
 */
@Mixin(RenderArrow.class)
public abstract class RenderArrowMixin {

    @Inject(
        method = "doRender(Lnet/minecraft/entity/projectile/EntityArrow;DDDFF)V",
        at = @At("HEAD"),
        cancellable = true)
    private void futura3d$replace(EntityArrow arrow, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        if (EntityReplacements.INSTANCE.renderProjectile(arrow, x, y, z, partialTicks)) {
            ci.cancel();
        }
    }
}

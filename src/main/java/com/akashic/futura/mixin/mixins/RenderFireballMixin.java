package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.entity.RenderFireball;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.EntityReplacements;

/**
 * A thrown projectile (fireball, small fireball) whose thrower the
 * server named (protocol 3) is drawn with the thrower's projectile replacement, if his model has one for it; otherwise
 * the vanilla renderer draws it.
 */
@Mixin(RenderFireball.class)
public abstract class RenderFireballMixin {

    @Inject(
        method = "doRender(Lnet/minecraft/entity/projectile/EntityFireball;DDDFF)V",
        at = @At("HEAD"),
        cancellable = true)
    private void futura3d$replace(net.minecraft.entity.projectile.EntityFireball entity, double x, double y, double z,
        float yaw, float partialTicks, CallbackInfo ci) {
        if (EntityReplacements.INSTANCE.renderProjectile(entity, x, y, z, partialTicks)) {
            ci.cancel();
        }
    }
}

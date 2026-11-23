package com.akashic.futura.mixin.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.ViewCamera;

/**
 * The first-person camera height from the model: once {@code orientCamera} has placed the camera (rotations and the
 * eye translation), {@link ViewCamera} adds a vertical move. {@code ActiveRenderInfo}, the frustum and the fog read
 * the matrix after this, so they follow it; so do the pick rays, the entity one here and the block one in
 * {@link EntityLivingBasePickMixin}.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "orientCamera(F)V", at = @At("RETURN"))
    private void futura3d$viewLocator(float partialTicks, CallbackInfo ci) {
        ViewCamera.INSTANCE.afterOrientCamera(partialTicks);
    }

    /**
     * The entity pick ray of {@code getMouseOver} (its first {@code Vec3}, {@code renderViewEntity.getPosition}), from
     * the camera; at the store, after the Akashic Player Backport's own wrap of that call.
     */
    @ModifyVariable(method = "getMouseOver(F)V", at = @At("STORE"), ordinal = 0)
    private Vec3 futura3d$pickFromCamera(Vec3 origin) {
        return ViewCamera.INSTANCE.pickOrigin(Minecraft.getMinecraft().renderViewEntity, origin, false);
    }
}

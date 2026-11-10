package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.ViewCamera;

/**
 * The first-person camera height from the model: once {@code orientCamera} has placed the camera (rotations and the
 * eye translation), {@link ViewCamera} adds a vertical move. {@code ActiveRenderInfo}, the frustum and the fog read
 * the matrix after this, so they follow it; block picking does not (render only).
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "orientCamera(F)V", at = @At("RETURN"))
    private void futura3d$viewLocator(float partialTicks, CallbackInfo ci) {
        ViewCamera.INSTANCE.afterOrientCamera(partialTicks);
    }
}

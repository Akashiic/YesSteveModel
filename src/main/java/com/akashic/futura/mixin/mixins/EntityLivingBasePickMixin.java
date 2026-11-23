package com.akashic.futura.mixin.mixins;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.akashic.futura.client.ViewCamera;

/**
 * The block pick ray ({@code rayTrace}, which {@code EntityRenderer.getMouseOver} calls for the block under the
 * crosshair) starts where {@link ViewCamera} put the camera. Without it the outline and the side or half of a block
 * that is placed are picked from the vanilla eye, a few centimetres off the crosshair.
 *
 * <p>
 * At the store of {@code getPosition}'s result, after it: the Akashic Player Backport's coremod wraps that call itself
 * (pose-aware origin) and checks only the instructions before it, so the two compose in either order.
 */
@Mixin(EntityLivingBase.class)
public abstract class EntityLivingBasePickMixin {

    @ModifyVariable(method = "rayTrace(DF)Lnet/minecraft/util/MovingObjectPosition;", at = @At("STORE"), ordinal = 0)
    private Vec3 futura3d$pickFromCamera(Vec3 origin) {
        return ViewCamera.INSTANCE.pickOrigin((Entity) (Object) this, origin, true);
    }
}

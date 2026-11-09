package com.akashic.futura.mixin.mixins;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.akashic.futura.client.PlayerModels;

/**
 * Swaps the biped for the player's model. Only the inner {@code super.doRender} call of
 * {@code RenderPlayer.doRender} is replaced, so {@code RenderPlayerEvent.Pre} and {@code Post} still fire for other
 * mods, and the shadow and fire (drawn by the render manager afterwards) stay vanilla.
 */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerMixin extends RendererLivingEntity {

    private RenderPlayerMixin(ModelBase model, float shadow) {
        super(model, shadow);
    }

    @Redirect(
        method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V"))
    private void futura3d$drawModel(RendererLivingEntity self, EntityLivingBase entity, double x, double y, double z,
        float yaw, float partialTicks) {
        if (PlayerModels.INSTANCE.render((AbstractClientPlayer) entity, x, y, z, partialTicks)) {
            // RendererLivingEntity.doRender ends with the name tag: keep it.
            this.passSpecialRender(entity, x, y, z);
        } else {
            super.doRender(entity, x, y, z, yaw, partialTicks);
        }
    }
}

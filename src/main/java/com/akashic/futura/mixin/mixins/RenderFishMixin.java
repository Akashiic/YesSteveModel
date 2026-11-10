package com.akashic.futura.mixin.mixins;

import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderFish;
import net.minecraft.entity.projectile.EntityFishHook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.EntityReplacements;
import com.akashic.futura.client.FishingLine;

/**
 * The fishing line of a player drawn with a model starts at the rod in the model's hand ({@link FishingLine}). Only
 * the line's {@code addVertex} calls are redirected, never the {@code startDrawing} call another mod may inject at to
 * draw a line of its own. When the angler's model replaces the bobber ({@code minecraft:fishing_bobber} in
 * {@code files.projectiles}), the model is drawn and the vanilla bobber's {@code addVertexWithUV} calls are skipped;
 * the line is drawn as before (upstream {@code FishingHookRendererReplace} also draws the bobber, then the line).
 */
@Mixin(RenderFish.class)
public abstract class RenderFishMixin {

    @Unique
    private boolean futura3d$bobberReplaced;

    @Inject(method = "doRender(Lnet/minecraft/entity/projectile/EntityFishHook;DDDFF)V", at = @At("HEAD"))
    private void futura3d$lineStart(EntityFishHook hook, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        futura3d$bobberReplaced = EntityReplacements.INSTANCE.renderProjectile(hook, x, y, z, partialTicks);
        FishingLine.begin(hook, x, y, z);
    }

    @Redirect(
        method = "doRender(Lnet/minecraft/entity/projectile/EntityFishHook;DDDFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;addVertex(DDD)V"))
    private void futura3d$lineVertex(Tessellator tess, double x, double y, double z) {
        FishingLine.vertex(tess, x, y, z);
    }

    @Redirect(
        method = "doRender(Lnet/minecraft/entity/projectile/EntityFishHook;DDDFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;addVertexWithUV(DDDDD)V"))
    private void futura3d$bobberVertex(Tessellator tess, double x, double y, double z, double u, double v) {
        if (!futura3d$bobberReplaced) {
            tess.addVertexWithUV(x, y, z, u, v);
        }
    }

    @Inject(method = "doRender(Lnet/minecraft/entity/projectile/EntityFishHook;DDDFF)V", at = @At("RETURN"))
    private void futura3d$end(EntityFishHook hook, double x, double y, double z, float yaw, float partialTicks,
        CallbackInfo ci) {
        futura3d$bobberReplaced = false;
    }
}

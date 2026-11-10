package com.akashic.futura.mixin.mixins;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A memory leak of vanilla 1.7.10: {@code WorldClient.addEntityToWorld} puts the entity in its {@code entityList} (a
 * hash set; an entity's hash is its id) before giving it the server's id, so {@code removeEntityFromWorld} never finds
 * it again. Players ({@code S0CPacketSpawnPlayer}) and paintings come this way: every player who ever came into view
 * stayed in memory, with his inventory and everything that refers to him, until the world was left. Giving the id first
 * is what vanilla's other spawn handlers already do; doing it twice changes nothing.
 */
@Mixin(WorldClient.class)
public abstract class WorldClientEntityIdMixin {

    @Inject(method = "addEntityToWorld(ILnet/minecraft/entity/Entity;)V", at = @At("HEAD"))
    private void futura3d$idFirst(int id, Entity entity, CallbackInfo ci) {
        if (entity != null) {
            entity.setEntityId(id);
        }
    }
}

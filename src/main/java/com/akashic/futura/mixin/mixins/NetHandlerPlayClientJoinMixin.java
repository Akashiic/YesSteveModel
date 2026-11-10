package com.akashic.futura.mixin.mixins;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S01PacketJoinGame;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.akashic.futura.client.net.ClientNetwork;

/**
 * Every join of a server, the first one and each one a proxy (Velocity) does on the same connection when it moves the
 * player to another server: it sends a new {@code S01PacketJoinGame}. The model session starts again from there, so a
 * server without the mod (a Paper lobby) never keeps the models and selections of the server before it. An FML
 * handshake reset, the other sign of a switch, only comes when the next server is modded.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientJoinMixin {

    @Inject(method = "handleJoinGame(Lnet/minecraft/network/play/server/S01PacketJoinGame;)V", at = @At("TAIL"))
    private void futura3d$joined(S01PacketJoinGame packet, CallbackInfo ci) {
        ClientNetwork.INSTANCE.serverJoined();
    }
}

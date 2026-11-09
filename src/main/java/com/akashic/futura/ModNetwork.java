package com.akashic.futura;

import com.akashic.futura.net.Message;
import com.akashic.futura.net.MessageCodec;
import com.akashic.futura.net.Protocol;

import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The model channel ({@link Protocol#CHANNEL}), an FML event-driven channel, shared by both sides.
 * <p>
 * Why FML's channel and not raw custom payload packets: FML's {@code NetworkDispatcher} sits in the connection's
 * netty pipeline on Forge, Thermos and Crucible alike, and hands packets of registered channels to the mod before the
 * vanilla handler (which Thermos patches to feed Bukkit's plugin messaging) sees them, in both directions; a raw
 * {@code C17PacketCustomPayload} would need a hook in {@code NetHandlerPlayServer.processVanilla250Packet}, the method
 * Thermos rewrites. The packets are queued and handled on the main threads (an {@code FMLProxyPacket} has no
 * priority), so handlers need no locking. The same path serves the integrated server of singleplayer.
 */
public final class ModNetwork {

    private static FMLEventChannel channel;

    private ModNetwork() {}

    /** Creates the channel (init, both sides). */
    public static void init() {
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(Protocol.CHANNEL);
    }

    public static FMLEventChannel channel() {
        return channel;
    }

    /** A fresh packet for a message (a packet is consumed when sent: one per recipient). */
    public static FMLProxyPacket packet(Message m) {
        return new FMLProxyPacket(Unpooled.wrappedBuffer(MessageCodec.encode(m)), Protocol.CHANNEL);
    }

    /** The payload's bytes, without moving its reader index. */
    public static byte[] payload(FMLProxyPacket packet) {
        ByteBuf buf = packet.payload();
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }
}

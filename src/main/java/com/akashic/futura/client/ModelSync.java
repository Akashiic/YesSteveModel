package com.akashic.futura.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;

import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.SyncSink;

/**
 * The client end of {@code ysm.sync}: the seam the network layer plugs into. While a {@link Transport} is connected,
 * the local player's {@code ysm.sync(values)} goes to it (and comes back for every client through {@link #receive}),
 * and other players' models do not sync by themselves; without one, the values reach the model's {@code @sync}
 * handlers at once, as upstream does without a server.
 */
public final class ModelSync {

    /** Implemented by the network layer. */
    public interface Transport {

        /** Whether the server relays syncs now (it has the mod and speaks a protocol with sync). */
        boolean connected();

        /** Sends the local player's values (0 to 16 finite numbers) to the server. */
        void send(float[] values);
    }

    private static volatile Transport transport;

    private ModelSync() {}

    /** @param t the network's transport, or null to dispatch locally */
    public static void setTransport(Transport t) {
        transport = t;
    }

    /** The sink of one player's animator. */
    static SyncSink sinkFor(AbstractClientPlayer player) {
        // Weak, as ModelSounds.backend: the sink lives in the player's animator, held by a map weakly keyed by him.
        java.lang.ref.WeakReference<AbstractClientPlayer> ref = new java.lang.ref.WeakReference<>(player);
        return values -> {
            Transport t = transport;
            if (t == null || !t.connected()) {
                return false;
            }
            AbstractClientPlayer p = ref.get();
            if (p != null && p == Minecraft.getMinecraft().thePlayer) {
                t.send(values);
            }
            return true;
        };
    }

    /**
     * Runs a player's {@code @sync} handlers with values relayed by the server (render thread).
     *
     * @return false if the player has no animator (not drawn yet)
     */
    public static boolean receive(AbstractClientPlayer player, float[] values) {
        PlayerAnimator animator = player == null ? null : PlayerModels.INSTANCE.animator(player);
        if (animator == null) {
            return false;
        }
        animator.dispatchSync(values);
        return true;
    }
}

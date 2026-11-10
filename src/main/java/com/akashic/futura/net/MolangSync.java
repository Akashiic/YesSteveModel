package com.akashic.futura.net;

import java.util.Arrays;

import com.akashic.futura.molang.MolangValue;

/**
 * The seam between {@code ysm.sync(...)} in a model's Molang and the network, without Minecraft.
 * <p>
 * Upstream ({@code ModelBuiltins.sync}): the local player's model calls {@code ysm.sync(f...)} with up to 16 numbers;
 * the client sends them to the server ({@code EmitMolangSync}), which passes them to every client that sees the
 * player and back to the sender ({@code MolangSyncEvent}); each one then runs that player's model's {@code sync}
 * handlers with the numbers. Without a server that speaks it, the handlers run at once, locally. Never Molang source
 * on the wire: numbers only.
 * <p>
 * Here: {@link #emit} is what the Molang host calls (the animation runtime, through its {@code ysm.sync}); the client
 * network installs the {@link Emitter} (it knows whether the animator is the local player's and whether the server
 * speaks protocol 2). {@link #receive} is what the network calls when a sync comes back; it hands the numbers to the
 * {@link Receiver}, which runs the model's {@code sync} handlers. <b>Reconcile at merge:</b> the handlers (the
 * model's {@code functions}/events with a {@code sync} route) belong to the animation runtime's user functions; until
 * that lands the default receiver only records the last event per target ({@link #lastReceived}), so tests and the
 * harness can see it arrive.
 */
public final class MolangSync {

    /** Where a model's {@code ysm.sync} goes: the client network. */
    public interface Emitter {

        /**
         * @param source the animator (or other owner) whose model called it; only the local player's go anywhere
         * @param args   at most {@link Protocol#MAX_SYNC_ARGS} finite numbers
         */
        void emit(Object source, float[] args);
    }

    /** Runs a model's {@code sync} handlers for a target (an animator) with the event's numbers. */
    public interface Receiver {

        void receive(Object target, float[] args);
    }

    private static volatile Emitter emitter;
    private static volatile Receiver receiver;
    private static volatile Object lastTarget;
    private static volatile float[] lastArgs;

    private MolangSync() {}

    public static void setEmitter(Emitter e) {
        emitter = e;
    }

    public static void setReceiver(Receiver r) {
        receiver = r;
    }

    /**
     * {@code ysm.sync(args...)}: the arguments as Molang passed them; numbers past {@link Protocol#MAX_SYNC_ARGS}
     * are dropped and anything that is not a finite number (a string, a vector) is 0. Does nothing without an emitter.
     */
    public static void emit(Object source, MolangValue[] args) {
        Emitter e = emitter;
        if (e == null) {
            return;
        }
        e.emit(source, toFloats(args));
    }

    /** The numbers a sync carries. */
    public static float[] toFloats(MolangValue[] args) {
        int n = args == null ? 0 : Math.min(args.length, Protocol.MAX_SYNC_ARGS);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            MolangValue v = args[i];
            float f = v == null || v.kind != MolangValue.Kind.NUMBER ? 0 : (float) v.asNumber();
            out[i] = MessageCodec.finite(f) ? f : 0;
        }
        return out;
    }

    /** A sync event for a target arrived (or ran locally): hands it to the receiver. */
    public static void receive(Object target, float[] args) {
        lastTarget = target;
        lastArgs = args.clone();
        Receiver r = receiver;
        if (r != null) {
            r.receive(target, args);
        }
    }

    /** The target of the last event received, or null. */
    public static Object lastTarget() {
        return lastTarget;
    }

    /** The numbers of the last event received (a copy), or null. */
    public static float[] lastReceived() {
        float[] a = lastArgs;
        return a == null ? null : Arrays.copyOf(a, a.length);
    }
}

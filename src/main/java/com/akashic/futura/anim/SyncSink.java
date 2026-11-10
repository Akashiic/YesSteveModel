package com.akashic.futura.anim;

/**
 * Where {@code ysm.sync(values...)} goes. Upstream sends the values of the local player to the server, which hands them
 * to every client, and each client then runs the model's {@code @sync} handlers for that player
 * ({@link PlayerAnimator#dispatchSync}); without the server side, the handlers run locally at once. The network layer
 * implements this seam; with no sink (or one that declines) the values are dispatched locally.
 */
@FunctionalInterface
public interface SyncSink {

    /**
     * @param values 0 to 16 finite numbers
     * @return true if the sink took the values (they come back through {@link PlayerAnimator#dispatchSync}, or are
     *         dropped on purpose, as for another player's model while connected); false to dispatch them locally now
     */
    boolean emit(float[] values);
}

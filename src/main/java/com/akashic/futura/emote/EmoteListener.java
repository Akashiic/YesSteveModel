package com.akashic.futura.emote;

/**
 * Hears the local player start and stop extra (emote) animations, from the roulette, the extra keys or a movement key.
 * <p>
 * Seam for phase 4: emotes are local only for now (the local player's own animator plays them at once). The network
 * layer registers a listener that sends the request to the server (upstream {@code ClientProtocolGateway
 * .playSelfAnimation}/{@code stopSelfAnimation}); the server relays it, and each receiving client calls
 * {@code PlayerModels.INSTANCE.animator(player).playExtra(name)} / {@code stopExtra()} for that player.
 */
public interface EmoteListener {

    /** The local player started extra animation {@code name} (already playing locally). */
    void played(String name);

    /** The local player's extra animation was stopped (already stopped locally). */
    void stopped();
}

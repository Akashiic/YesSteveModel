package com.akashic.futura.anim;

/**
 * Plays the sounds a model asks for (sound keyframes, controller-state sounds, {@code ysm.play_sound}), at the player
 * it animates. The client implements it with Minecraft's sound system; without one ({@link #NONE}) nothing plays.
 */
public interface SoundBackend {

    /** Plays nothing. */
    SoundBackend NONE = (sound, volume, pitch, loop) -> null;

    /**
     * Starts a sound.
     *
     * @param sound  a sound of the model, by its name under the model's sound folder without extension
     *               ({@code horn}), or a Minecraft sound id when it contains {@code :} ({@code minecraft:random.click})
     * @param volume author volume (0.001 to 1000, as upstream clamps it)
     * @param pitch  author pitch (0.001 to 1000)
     * @param loop   whether it repeats until stopped
     * @return a handle, or null when it could not start (unknown sound, too many sounds, no sound system)
     */
    SoundHandle play(String sound, float volume, float pitch, boolean loop);

    /** One sound started by a {@link SoundBackend}. */
    interface SoundHandle {

        /** true once it ended by itself or was stopped. */
        boolean isStopped();

        /** Stops it (nothing if already stopped). */
        void stop();
    }
}

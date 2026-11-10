package com.akashic.futura.model;

import java.nio.charset.StandardCharsets;

/**
 * What a sound file is, from its first Ogg page: Vorbis goes to 1.7.10's own codec (JOrbis), Opus to the mod's
 * ({@code client/CodecOggOpus}, Concentus).
 */
public final class OggKind {

    public static final String VORBIS = "vorbis";
    public static final String OPUS = "opus";
    public static final String OTHER = "other";

    private OggKind() {}

    /** {@link #VORBIS}, {@link #OPUS} or {@link #OTHER} (not Ogg, or another codec). */
    public static String of(byte[] data) {
        if (data == null || data.length < 28 || data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S') {
            return OTHER;
        }
        int segments = data[26] & 0xff;
        int packet = 27 + segments;
        if (startsWith(data, packet, new byte[] { 1, 'v', 'o', 'r', 'b', 'i', 's' })) {
            return VORBIS;
        }
        if (startsWith(data, packet, "OpusHead".getBytes(StandardCharsets.US_ASCII))) {
            return OPUS;
        }
        return OTHER;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (offset + prefix.length > data.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

package com.akashic.futura.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.junit.jupiter.api.Test;

import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.OggKind;

import paulscode.sound.SoundBuffer;
import paulscode.sound.codecs.CodecJOrbis;

/** A model's Ogg Vorbis file through the in-memory URL and 1.7.10's own Vorbis codec, as the sound system reads it. */
class SoundDecodeTest {

    private static byte[] read(String model, String path) throws IOException {
        try (InputStream in = new ClasspathModelSource("/assets/futura3d/builtin/" + model + "/").open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Test
    void vorbisFileDecodesThroughTheUrl() throws IOException {
        byte[] data = read("wine_fox/21_saint", "sounds/atk1.ogg");
        assertEquals(OggKind.VORBIS, OggKind.of(data));
        URL url = SoundBytesUrl.of("futura3d_test_atk1.ogg", data);
        CodecJOrbis codec = new CodecJOrbis();
        assertTrue(codec.initialize(url), "codec opened the URL");
        SoundBuffer all = codec.readAll();
        codec.cleanup();
        assertNotNull(all);
        assertTrue(all.audioData.length > 10_000, "decoded bytes: " + all.audioData.length);
        float rate = all.audioFormat.getSampleRate();
        assertTrue(rate == 44100 || rate == 48000 || rate == 22050, "rate " + rate);
        // not silence
        int loud = 0;
        for (int i = 0; i + 1 < all.audioData.length; i += 2) {
            short s = (short) ((all.audioData[i + 1] << 8) | (all.audioData[i] & 0xff));
            if (Math.abs(s) > 1000) {
                loud++;
            }
        }
        assertTrue(loud > 100, "loud samples: " + loud);
    }

    @Test
    void opusFilesAreRecognised() throws IOException {
        assertEquals(OggKind.OPUS, OggKind.of(read("wine_fox/14_momo", "sounds/fly_on.ogg")));
        assertEquals(OggKind.OTHER, OggKind.of(new byte[] { 'R', 'I', 'F', 'F' }));
    }
}

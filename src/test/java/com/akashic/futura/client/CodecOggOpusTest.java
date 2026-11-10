package com.akashic.futura.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.akashic.futura.model.ClasspathModelSource;

import paulscode.sound.SoundBuffer;
import paulscode.sound.SoundSystemConfig;

/** The Opus codec as paulscode drives it: registered by extension, fed through the in-memory URL. */
class CodecOggOpusTest {

    private static byte[] momo(String name) throws IOException {
        try (InputStream in = new ClasspathModelSource("/assets/futura3d/builtin/wine_fox/14_momo/")
            .open("sounds/" + name + ".ogg")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Samples per channel the file should give: last granule minus pre-skip. */
    private static long expectedFrames(byte[] data) {
        long granule = 0;
        for (int i = data.length - 27; i >= 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                for (int k = 7; k >= 0; k--) {
                    granule = granule << 8 | (data[i + 6 + k] & 0xff);
                }
                break;
            }
        }
        int packet = 27 + (data[26] & 0xff);
        int preSkip = (data[packet + 10] & 0xff) | (data[packet + 11] & 0xff) << 8;
        return granule - preSkip;
    }

    @Test
    void registeredForTheOpusExtensionOnly() {
        assertTrue(CodecOggOpus.register());
        assertTrue(CodecOggOpus.registered());
        assertTrue(SoundSystemConfig.getCodec("futura3d_0_1a2b.opus") instanceof CodecOggOpus);
        assertFalse(SoundSystemConfig.getCodec("futura3d_0_1a2b.ogg") instanceof CodecOggOpus);
    }

    @Test
    void smallFileDecodesWhole() throws IOException {
        byte[] data = momo("fly_on");
        URL url = SoundBytesUrl.of("futura3d_test_fly_on.opus", data);
        CodecOggOpus codec = new CodecOggOpus();
        codec.reverseByteOrder(true); // what LibraryLWJGLOpenAL asks; the output stays little-endian
        assertTrue(codec.initialize(url));
        assertTrue(codec.initialized());
        assertEquals(
            48000f,
            codec.getAudioFormat()
                .getSampleRate());
        assertEquals(
            1,
            codec.getAudioFormat()
                .getChannels());
        assertEquals(
            16,
            codec.getAudioFormat()
                .getSampleSizeInBits());
        assertFalse(
            codec.getAudioFormat()
                .isBigEndian());
        SoundBuffer all = codec.readAll();
        assertTrue(codec.endOfStream());
        assertNull(codec.read());
        codec.cleanup();
        assertNotNull(all);
        assertEquals(expectedFrames(data) * 2, all.audioData.length);
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
    void largeFileStreamsInChunksAndLoops() throws IOException {
        byte[] data = momo("dooble");
        URL url = SoundBytesUrl.of("futura3d_test_dooble.opus", data);
        CodecOggOpus codec = new CodecOggOpus();
        for (int round = 0; round < 2; round++) {
            // a looping streaming source opens the URL again at the end
            assertTrue(codec.initialize(url));
            long total = 0;
            int chunks = 0;
            SoundBuffer chunk;
            while ((chunk = codec.read()) != null) {
                assertTrue(chunk.audioData.length <= SoundSystemConfig.getStreamingBufferSize() + 2 * 5760);
                assertEquals(0, chunk.audioData.length % 2);
                total += chunk.audioData.length;
                chunks++;
            }
            assertTrue(codec.endOfStream());
            assertEquals(expectedFrames(data) * 2, total);
            assertTrue(chunks > 10, "chunks: " + chunks);
        }
        codec.cleanup();
        assertFalse(codec.initialized());
    }

    @Test
    void damagedFilesDoNotThrow() throws IOException {
        byte[] data = momo("sfmb");
        CodecOggOpus codec = new CodecOggOpus();
        // cut in the middle: what was decoded plays, then the stream ends
        assertTrue(codec.initialize(SoundBytesUrl.of("futura3d_test_cut.opus", Arrays.copyOf(data, data.length / 3))));
        SoundBuffer part = codec.readAll();
        assertNotNull(part);
        assertTrue(part.audioData.length > 0 && part.audioData.length < expectedFrames(data) * 2);
        assertTrue(codec.endOfStream());
        codec.cleanup();
        // not Opus at all: initialize says no
        assertFalse(codec.initialize(SoundBytesUrl.of("futura3d_test_bad.opus", new byte[] { 1, 2, 3 })));
        assertFalse(codec.initialized());
        assertNull(codec.readAll());
    }
}

package com.akashic.futura.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.akashic.futura.model.ClasspathModelSource;

/**
 * The Ogg Opus decoder on the built-in Opus sounds (wine_fox/14_momo) and on small generated files (a stereo sine with
 * 440 Hz left and 660 Hz right, and a 5.1 file in channel mapping family 1, made with ffmpeg/libopus).
 */
class OggOpusDecoderTest {

    static byte[] builtin(String path) throws IOException {
        try (InputStream in = new ClasspathModelSource("/assets/futura3d/builtin/wine_fox/14_momo/").open(path)) {
            return readAll(in);
        }
    }

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = OggOpusDecoderTest.class.getResourceAsStream("/futura3d/audio/" + name)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }

    /** The granule position of the file's last page, found without the reader (scanning back for "OggS"). */
    static long lastGranule(byte[] data) {
        byte[] magic = "OggS".getBytes(StandardCharsets.US_ASCII);
        for (int i = data.length - 27; i >= 0; i--) {
            if (data[i] == magic[0] && data[i + 1] == magic[1] && data[i + 2] == magic[2] && data[i + 3] == magic[3]) {
                long g = 0;
                for (int k = 7; k >= 0; k--) {
                    g = g << 8 | (data[i + 6 + k] & 0xff);
                }
                return g;
            }
        }
        throw new AssertionError("no Ogg page");
    }

    /** The pre-skip field of the OpusHead, read straight from the first page. */
    static int preSkip(byte[] data) {
        int packet = 27 + (data[26] & 0xff);
        return (data[packet + 10] & 0xff) | (data[packet + 11] & 0xff) << 8;
    }

    /** All samples, interleaved. */
    static short[] decodeAll(OggOpusDecoder decoder) throws IOException {
        short[] buffer = new short[OggOpusDecoder.MAX_FRAME * decoder.channels()];
        short[] all = new short[1 << 16];
        int length = 0;
        int frames;
        while ((frames = decoder.read(buffer)) >= 0) {
            int samples = frames * decoder.channels();
            if (length + samples > all.length) {
                all = Arrays.copyOf(all, Math.max(all.length * 2, length + samples));
            }
            System.arraycopy(buffer, 0, all, length, samples);
            length += samples;
        }
        return Arrays.copyOf(all, length);
    }

    @ParameterizedTest
    @ValueSource(strings = { "fly_on", "sfmb", "dooble", "owo" })
    void builtinOpusSoundsDecodeToTheirGranuleLength(String name) throws IOException {
        byte[] data = builtin("sounds/" + name + ".ogg");
        try (OggOpusDecoder decoder = new OggOpusDecoder(new ByteArrayInputStream(data))) {
            assertEquals(0, decoder.mappingFamily());
            assertEquals(1, decoder.channels(), "the momo sounds are mono");
            assertEquals(preSkip(data), decoder.preSkip());
            short[] pcm = decodeAll(decoder);
            assertTrue(decoder.ended());
            assertEquals(lastGranule(data) - decoder.preSkip(), pcm.length, "samples = last granule - pre-skip");
            assertTrue(peak(pcm, 0, 1) > 1000, "not silence");
        }
    }

    @Test
    void stereoKeepsItsChannelsApart() throws IOException {
        byte[] data = fixture("stereo-sine.opus");
        try (OggOpusDecoder decoder = new OggOpusDecoder(new ByteArrayInputStream(data))) {
            assertEquals(2, decoder.channels());
            assertEquals(2, decoder.sourceChannels());
            short[] pcm = decodeAll(decoder);
            long frames = lastGranule(data) - decoder.preSkip();
            assertEquals(24000, frames, "0.5 s at 48 kHz");
            assertEquals(frames * 2, pcm.length);
            // 440 Hz left and 660 Hz right: count zero crossings in the middle 0.25 s (about 2 per period)
            assertEquals(220, crossings(pcm, 0, 2, 6000, 18000), 6);
            assertEquals(330, crossings(pcm, 1, 2, 6000, 18000), 6);
        }
    }

    @Test
    void surroundIsMixedDownToStereo() throws IOException {
        byte[] data = fixture("surround-sine.opus");
        try (OggOpusDecoder decoder = new OggOpusDecoder(new ByteArrayInputStream(data))) {
            assertEquals(1, decoder.mappingFamily());
            assertEquals(6, decoder.sourceChannels());
            assertEquals(2, decoder.channels());
            short[] pcm = decodeAll(decoder);
            assertEquals((lastGranule(data) - decoder.preSkip()) * 2, pcm.length);
            assertEquals(14400 * 2, pcm.length, "0.3 s at 48 kHz");
            assertTrue(peak(pcm, 0, 2) > 1000 && peak(pcm, 1, 2) > 1000, "both sides carry the signal");
            assertEquals(132, crossings(pcm, 0, 2, 2400, 12000), 6); // 330 Hz over 0.2 s
        }
    }

    @Test
    void truncatedFileFailsWithAnIOException() throws IOException {
        byte[] data = builtin("sounds/dooble.ogg");
        byte[] cut = Arrays.copyOf(data, data.length / 2 + 7); // in the middle of a page
        OggOpusDecoder decoder = new OggOpusDecoder(new ByteArrayInputStream(cut));
        short[] buffer = new short[OggOpusDecoder.MAX_FRAME];
        IOException e = assertThrows(IOException.class, () -> {
            while (true) {
                int n = decoder.read(buffer);
                if (n < 0) {
                    break;
                }
            }
        });
        assertTrue(
            e.getMessage()
                .contains("truncated"),
            e.getMessage());
        decoder.close();
    }

    @Test
    void fileCutBetweenPagesJustEndsEarly() throws IOException {
        byte[] data = builtin("sounds/dooble.ogg");
        // cut just before the last page
        int last = -1;
        for (int i = data.length - 27; i > 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                last = i;
                break;
            }
        }
        try (OggOpusDecoder decoder = new OggOpusDecoder(new ByteArrayInputStream(Arrays.copyOf(data, last)))) {
            short[] pcm = decodeAll(decoder);
            assertTrue(
                pcm.length > 0 && pcm.length < lastGranule(data) - decoder.preSkip(),
                "some audio: " + pcm.length);
        }
    }

    @Test
    void corruptPageFailsTheCrc() throws IOException {
        byte[] data = builtin("sounds/fly_on.ogg").clone();
        data[data.length - 40] ^= 0x5a; // inside the last page's body
        OggOpusDecoder decoder = new OggOpusDecoder(new ByteArrayInputStream(data));
        IOException e = assertThrows(IOException.class, () -> decodeAll(decoder));
        assertTrue(
            e.getMessage()
                .contains("CRC"),
            e.getMessage());
    }

    @Test
    void notOpusIsRefused() throws IOException {
        byte[] vorbis;
        try (InputStream in = new ClasspathModelSource("/assets/futura3d/builtin/wine_fox/21_saint/")
            .open("sounds/atk1.ogg")) {
            vorbis = readAll(in);
        }
        IOException e = assertThrows(IOException.class, () -> OggOpusDecoder.probe(vorbis));
        assertTrue(
            e.getMessage()
                .contains("OpusHead"),
            e.getMessage());
        assertThrows(IOException.class, () -> OggOpusDecoder.probe(new byte[] { 'R', 'I', 'F', 'F', 0, 0, 0, 0 }));
        assertThrows(IOException.class, () -> OggOpusDecoder.probe(new byte[0]));
    }

    @Test
    void unsupportedMappingFamilyIsRefused() throws IOException {
        byte[] data = fixture("stereo-sine.opus").clone();
        int head = 27 + (data[26] & 0xff);
        data[head + 18] = (byte) 255; // channel mapping family 255
        fixCrc(data, 0);
        IOException e = assertThrows(IOException.class, () -> OggOpusDecoder.probe(data));
        assertTrue(
            e.getMessage()
                .contains("family 255"),
            e.getMessage());
    }

    @Test
    void packetsSpanningPagesAreJoined() throws IOException {
        // two packets: one of 600 bytes (laced 255, 255, 90) split over two pages, then one of 3 bytes
        byte[] big = new byte[600];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePage(out, 0x02, -1, new int[] { 255, 255 }, Arrays.copyOfRange(big, 0, 510));
        byte[] rest = new byte[93];
        System.arraycopy(big, 510, rest, 0, 90);
        rest[90] = 7;
        rest[91] = 8;
        rest[92] = 9;
        writePage(out, 0x01 | 0x04, 1234, new int[] { 90, 3 }, rest);
        OggPacketReader reader = new OggPacketReader(new ByteArrayInputStream(out.toByteArray()));
        OggPacketReader.Packet first = reader.next();
        assertTrue(Arrays.equals(big, first.data));
        assertEquals(-1, first.granule);
        OggPacketReader.Packet second = reader.next();
        assertEquals(3, second.data.length);
        assertEquals(1234, second.granule);
        assertTrue(second.last);
        assertEquals(null, reader.next());
    }

    private static void writePage(ByteArrayOutputStream out, int type, long granule, int[] lacing, byte[] body) {
        byte[] page = new byte[27 + lacing.length + body.length];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[5] = (byte) type;
        for (int k = 0; k < 8; k++) {
            page[6 + k] = (byte) (granule >> (8 * k));
        }
        page[14] = 42; // serial
        page[26] = (byte) lacing.length;
        for (int i = 0; i < lacing.length; i++) {
            page[27 + i] = (byte) lacing[i];
        }
        System.arraycopy(body, 0, page, 27 + lacing.length, body.length);
        fixCrc(page, 0);
        out.write(page, 0, page.length);
    }

    /** Recomputes the CRC of the page at {@code at}. */
    static void fixCrc(byte[] data, int at) {
        int segments = data[at + 26] & 0xff;
        int length = 27 + segments;
        for (int i = 0; i < segments; i++) {
            length += data[at + 27 + i] & 0xff;
        }
        for (int i = 22; i < 26; i++) {
            data[at + i] = 0;
        }
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= (data[at + i] & 0xff) << 24;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04c11db7 : crc << 1;
            }
        }
        for (int k = 0; k < 4; k++) {
            data[at + 22 + k] = (byte) (crc >> (8 * k));
        }
    }

    private static int peak(short[] pcm, int channel, int channels) {
        int peak = 0;
        for (int i = channel; i < pcm.length; i += channels) {
            peak = Math.max(peak, Math.abs(pcm[i]));
        }
        return peak;
    }

    private static int crossings(short[] pcm, int channel, int channels, int fromFrame, int toFrame) {
        int count = 0;
        for (int f = fromFrame + 1; f < toFrame; f++) {
            short a = pcm[(f - 1) * channels + channel], b = pcm[f * channels + channel];
            if ((a < 0) != (b < 0)) {
                count++;
            }
        }
        return count;
    }
}

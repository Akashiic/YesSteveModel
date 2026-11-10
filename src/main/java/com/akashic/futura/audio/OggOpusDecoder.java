package com.akashic.futura.audio;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusMSDecoder;

/**
 * Decodes an Ogg Opus file (RFC 7845) to 16-bit PCM at 48 kHz with Concentus, the pure-Java port of libopus: reads the
 * {@code OpusHead} and {@code OpusTags} headers, then one audio packet per {@link #read}. Applies the header's output
 * gain, drops the pre-skip at the start and trims the end to the last page's granule position. Channel mapping family
 * 0 (mono, stereo) comes out as is; family 1 (up to 8 channels, Vorbis order) is mixed down to stereo; other families
 * are refused. Streams: only the current packet is held in memory. No Minecraft classes.
 */
public final class OggOpusDecoder implements Closeable {

    /** Opus always decodes at this rate here (what the encoder's input rate was is only informative). */
    public static final int SAMPLE_RATE = 48000;
    /** The longest Opus packet: 120 ms at 48 kHz, in samples per channel. */
    public static final int MAX_FRAME = 5760;

    private final InputStream in;
    private final OggPacketReader reader;
    private final int sourceChannels;
    private final int channels;
    private final int preSkip;
    private final int outputGain;
    private final int inputSampleRate;
    private final int family;
    private final OpusDecoder decoder;
    private final OpusMSDecoder multistream;
    /** Family 1 mixed to stereo: gain of each source channel into left and right. */
    private final float[][] downmix;
    private final short[] scratch;
    /** Samples per channel decoded so far, pre-skip included: the granule position of the last packet read. */
    private long position;
    private boolean ended;

    /** Reads the two header packets; the stream is closed by {@link #close}. */
    public OggOpusDecoder(InputStream in) throws IOException {
        this.in = in;
        this.reader = new OggPacketReader(in);
        OggPacketReader.Packet head = reader.next();
        if (head == null || !startsWith(head.data, "OpusHead")) {
            throw new IOException("not an Ogg Opus stream (no OpusHead)");
        }
        byte[] h = head.data;
        if (h.length < 19) {
            throw new IOException("OpusHead too short");
        }
        int version = h[8] & 0xff;
        if (version >> 4 != 0) {
            throw new IOException("unsupported Ogg Opus version " + version);
        }
        sourceChannels = h[9] & 0xff;
        preSkip = (h[10] & 0xff) | (h[11] & 0xff) << 8;
        inputSampleRate = (h[12] & 0xff) | (h[13] & 0xff) << 8 | (h[14] & 0xff) << 16 | (h[15] & 0xff) << 24;
        outputGain = (short) ((h[16] & 0xff) | (h[17] & 0xff) << 8);
        family = h[18] & 0xff;
        try {
            if (family == 0) {
                if (sourceChannels < 1 || sourceChannels > 2) {
                    throw new IOException("channel mapping family 0 with " + sourceChannels + " channels");
                }
                channels = sourceChannels;
                decoder = new OpusDecoder(SAMPLE_RATE, sourceChannels);
                decoder.setGain(outputGain);
                multistream = null;
                downmix = null;
            } else if (family == 1) {
                if (sourceChannels < 1 || sourceChannels > 8) {
                    throw new IOException("channel mapping family 1 with " + sourceChannels + " channels");
                }
                if (h.length < 21 + sourceChannels) {
                    throw new IOException("OpusHead too short for its channel mapping table");
                }
                int streams = h[19] & 0xff;
                int coupled = h[20] & 0xff;
                if (streams < 1 || coupled > streams || streams + coupled > 255) {
                    throw new IOException("bad stream counts " + streams + "/" + coupled);
                }
                short[] mapping = new short[sourceChannels];
                for (int c = 0; c < sourceChannels; c++) {
                    int m = h[21 + c] & 0xff;
                    if (m != 255 && m >= streams + coupled) {
                        throw new IOException("bad channel mapping " + m);
                    }
                    mapping[c] = (short) m;
                }
                channels = Math.min(sourceChannels, 2);
                decoder = null;
                multistream = OpusMSDecoder.create(SAMPLE_RATE, sourceChannels, streams, coupled, mapping);
                multistream.setGain(outputGain);
                downmix = sourceChannels > 2 ? downmix(sourceChannels) : null;
            } else {
                throw new IOException("unsupported channel mapping family " + family);
            }
        } catch (OpusException | RuntimeException e) {
            throw new IOException("cannot create the Opus decoder: " + e.getMessage(), e);
        }
        scratch = new short[MAX_FRAME * sourceChannels];
        OggPacketReader.Packet tags = reader.next();
        if (tags == null || !startsWith(tags.data, "OpusTags")) {
            throw new IOException("Ogg Opus stream without OpusTags");
        }
    }

    /** Checks that the bytes start an Ogg Opus stream this decoder plays (headers only). */
    public static void probe(byte[] data) throws IOException {
        new OggOpusDecoder(new ByteArrayInputStream(data)).close();
    }

    /** Output channels: 1 or 2. */
    public int channels() {
        return channels;
    }

    /** Channels in the file (family 1 above 2 are mixed down to stereo). */
    public int sourceChannels() {
        return sourceChannels;
    }

    public int preSkip() {
        return preSkip;
    }

    /** The header's output gain, in 1/256 dB (Q7.8). */
    public int outputGain() {
        return outputGain;
    }

    /** The encoder's input rate, informative only: the output is always {@link #SAMPLE_RATE}. */
    public int inputSampleRate() {
        return inputSampleRate;
    }

    public int mappingFamily() {
        return family;
    }

    /** True once {@link #read} returned -1. */
    public boolean ended() {
        return ended;
    }

    /**
     * Decodes the next audio packet into {@code out}, interleaved, {@link #channels()} samples per frame.
     *
     * @param out at least {@link #MAX_FRAME} × {@link #channels()} long
     * @return frames written (0 for a packet that is all pre-skip or trimmed), or -1 at the end of the stream
     * @throws IOException on a damaged or truncated file
     */
    public int read(short[] out) throws IOException {
        if (out.length < MAX_FRAME * channels) {
            throw new IllegalArgumentException("output buffer shorter than " + MAX_FRAME * channels);
        }
        while (!ended) {
            OggPacketReader.Packet packet = reader.next();
            if (packet == null) {
                ended = true;
                break;
            }
            if (packet.data.length == 0) {
                continue; // not a valid Opus packet in Ogg; nothing to decode
            }
            int frames;
            try {
                frames = decoder != null
                    ? decoder.decode(packet.data, 0, packet.data.length, scratch, 0, MAX_FRAME, false)
                    : multistream.decodeMultistream(packet.data, 0, packet.data.length, scratch, 0, MAX_FRAME, 0);
            } catch (OpusException | RuntimeException e) {
                throw new IOException("corrupt Opus packet: " + e.getMessage(), e);
            }
            if (frames < 0) {
                throw new IOException("corrupt Opus packet (error " + frames + ")");
            }
            long start = position;
            position += frames;
            int from = start < preSkip ? (int) Math.min(frames, preSkip - start) : 0;
            int to = frames;
            if (packet.last && packet.granule >= 0 && position > packet.granule) {
                // the end-of-stream page says where the audio stops: the rest of the last packet is padding
                to = (int) Math.max(0, frames - (position - packet.granule));
            }
            if (packet.last) {
                ended = true;
            }
            if (to > from) {
                copy(from, to, out);
                return to - from;
            }
        }
        return -1;
    }

    private void copy(int from, int to, short[] out) {
        if (downmix == null) {
            System.arraycopy(scratch, from * channels, out, 0, (to - from) * channels);
            return;
        }
        int o = 0;
        for (int f = from; f < to; f++) {
            float left = 0, right = 0;
            int base = f * sourceChannels;
            for (int c = 0; c < sourceChannels; c++) {
                left += scratch[base + c] * downmix[c][0];
                right += scratch[base + c] * downmix[c][1];
            }
            out[o++] = clamp(left);
            out[o++] = clamp(right);
        }
    }

    private static short clamp(float v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }

    /**
     * Stereo gains for the Vorbis channel order of family 1 (RFC 7845 5.1.1.2): fronts to their side, centre to both at
     * -3 dB, surrounds and rears to their side at -3 dB, rear centre to both at -6 dB, LFE dropped; then scaled so a
     * full-scale signal on every channel cannot clip.
     */
    private static float[][] downmix(int n) {
        final float s = 0.70710678f;
        float[][] g;
        switch (n) {
            case 3: // L C R
                g = new float[][] { { 1, 0 }, { s, s }, { 0, 1 } };
                break;
            case 4: // FL FR RL RR
                g = new float[][] { { 1, 0 }, { 0, 1 }, { s, 0 }, { 0, s } };
                break;
            case 5: // FL C FR RL RR
                g = new float[][] { { 1, 0 }, { s, s }, { 0, 1 }, { s, 0 }, { 0, s } };
                break;
            case 6: // FL C FR RL RR LFE
                g = new float[][] { { 1, 0 }, { s, s }, { 0, 1 }, { s, 0 }, { 0, s }, { 0, 0 } };
                break;
            case 7: // FL C FR SL SR RC LFE
                g = new float[][] { { 1, 0 }, { s, s }, { 0, 1 }, { s, 0 }, { 0, s }, { 0.5f, 0.5f }, { 0, 0 } };
                break;
            default: // 8: FL C FR SL SR RL RR LFE
                g = new float[][] { { 1, 0 }, { s, s }, { 0, 1 }, { s, 0 }, { 0, s }, { s, 0 }, { 0, s }, { 0, 0 } };
                break;
        }
        float sum = 0;
        for (float[] c : g) {
            sum += c[0];
        }
        for (float[] c : g) {
            c[0] /= sum;
            c[1] /= sum;
        }
        return g;
    }

    private static boolean startsWith(byte[] data, String magic) {
        byte[] m = magic.getBytes(StandardCharsets.US_ASCII);
        if (data.length < m.length) {
            return false;
        }
        for (int i = 0; i < m.length; i++) {
            if (data[i] != m[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        ended = true;
        in.close();
    }
}

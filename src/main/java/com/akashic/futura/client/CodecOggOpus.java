package com.akashic.futura.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;

import com.akashic.futura.audio.OggOpusDecoder;

import paulscode.sound.ICodec;
import paulscode.sound.SoundBuffer;
import paulscode.sound.SoundSystemConfig;
import paulscode.sound.SoundSystemException;
import paulscode.sound.SoundSystemLogger;

/**
 * A paulscode codec for Ogg Opus, registered for the {@code opus} extension: 1.7.10's sound system only has JOrbis
 * (Vorbis). The sound system makes one per sound and reads it like {@code CodecJOrbis}: {@link #readAll} for a sound it
 * keeps decoded, {@link #read} a chunk at a time for a streaming source (and opens the URL again to loop). Output is
 * 16-bit little-endian PCM at 48 kHz, mono or stereo. No Minecraft classes.
 */
public class CodecOggOpus implements ICodec {

    /** The extension (of the sound's identifier) the codec is registered for. */
    public static final String EXTENSION = "opus";

    private static volatile boolean registered;

    private volatile boolean initialized;
    private volatile boolean endOfStream;
    private OggOpusDecoder decoder;
    private AudioFormat format;
    private short[] pcm;

    /** Paulscode creates codecs by reflection. */
    public CodecOggOpus() {}

    /** Registers the codec with paulscode; false (and Opus sounds are skipped) if it refused. */
    public static synchronized boolean register() {
        if (!registered) {
            try {
                SoundSystemConfig.setCodec(EXTENSION, CodecOggOpus.class);
                registered = true;
            } catch (SoundSystemException | LinkageError e) {
                return false;
            }
        }
        return true;
    }

    public static boolean registered() {
        return registered;
    }

    /** Ignored: the output is always little-endian, the order OpenAL takes on the machines Minecraft runs on. */
    @Override
    public void reverseByteOrder(boolean b) {}

    @Override
    public boolean initialize(URL url) {
        cleanup();
        endOfStream = false;
        InputStream in = null;
        try {
            in = new BufferedInputStream(url.openStream());
            decoder = new OggOpusDecoder(in);
        } catch (IOException | RuntimeException e) {
            error("Cannot open Ogg Opus " + url + ": " + e.getMessage());
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {}
            }
            decoder = null;
            return false;
        }
        format = new AudioFormat(OggOpusDecoder.SAMPLE_RATE, 16, decoder.channels(), true, false);
        pcm = new short[OggOpusDecoder.MAX_FRAME * decoder.channels()];
        initialized = true;
        return true;
    }

    @Override
    public boolean initialized() {
        return initialized;
    }

    /** About one streaming buffer of audio, or null when the stream has ended. */
    @Override
    public SoundBuffer read() {
        return decode(SoundSystemConfig.getStreamingBufferSize());
    }

    /** The whole sound (up to paulscode's file size limit), or null. */
    @Override
    public SoundBuffer readAll() {
        return decode(SoundSystemConfig.getMaxFileSize());
    }

    private SoundBuffer decode(int limit) {
        if (!initialized || endOfStream || decoder == null) {
            return null;
        }
        byte[] bytes = new byte[Math.min(limit, 1 << 16) + pcm.length * 2];
        int length = 0;
        while (length < limit) {
            int frames;
            try {
                frames = decoder.read(pcm);
            } catch (IOException | RuntimeException e) {
                // a damaged or cut file: play what was decoded
                error("Ogg Opus stream damaged: " + e.getMessage());
                endOfStream = true;
                break;
            }
            if (frames < 0) {
                endOfStream = true;
                break;
            }
            int samples = frames * decoder.channels();
            if (length + samples * 2 > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, length + samples * 2));
            }
            for (int i = 0; i < samples; i++) {
                short s = pcm[i];
                bytes[length++] = (byte) s;
                bytes[length++] = (byte) (s >> 8);
            }
        }
        if (length == 0) {
            return null;
        }
        return new SoundBuffer(length == bytes.length ? bytes : Arrays.copyOf(bytes, length), format);
    }

    @Override
    public boolean endOfStream() {
        return endOfStream;
    }

    @Override
    public void cleanup() {
        initialized = false;
        if (decoder != null) {
            try {
                decoder.close();
            } catch (IOException ignored) {}
            decoder = null;
        }
    }

    @Override
    public AudioFormat getAudioFormat() {
        return format;
    }

    private static void error(String message) {
        SoundSystemLogger logger = SoundSystemConfig.getLogger();
        if (logger != null) {
            logger.errorMessage("CodecOggOpus", message, 0);
        }
    }
}

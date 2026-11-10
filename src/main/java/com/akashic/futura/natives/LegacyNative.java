package com.akashic.futura.natives;

import java.io.IOException;

/**
 * The legacy {@code .ysm} (v3, encrypted) loader in the native library: the decoder of YesSteveModel-Native
 * (Apache-2.0) with our exporter, see {@code native/}. The model is decoded in memory and comes back as the files of an
 * open-format model; nothing is written to disk.
 */
public final class LegacyNative {

    /** The native ABI this class speaks ({@code kAbiVersion} in native/src/jni_entry.cc). */
    static final int ABI = 1;

    private LegacyNative() {}

    static native int nAbiVersion();

    static native byte[] nConvert(byte[] source);

    /** Whether the library is loaded (loading it on first call). */
    public static boolean available() {
        return NativeLoader.load() == NativeLoader.State.LOADED;
    }

    /**
     * @param source the whole {@code .ysm} file
     * @return the exported files in the {@code F3DL} layout read by {@code model.legacy.LegacyArchive}
     * @throws IOException if the library is unavailable or the file cannot be decoded
     */
    public static byte[] convert(byte[] source) throws IOException {
        if (!available()) {
            throw new IOException(
                "native loader unavailable (" + NativeLoader.state() + ": " + NativeLoader.detail() + ")");
        }
        return nConvert(source);
    }
}

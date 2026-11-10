package com.akashic.futura.model.legacy;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * Legacy {@code .ysm} files, read into the files of an open-format model, in memory only. Routing as upstream's
 * {@code LegacyContainerHeader}: "YSGP" + version 1 or 2 is a raw bundle ({@link LegacyBundle}, pure Java), a UTF-8 BOM
 * + "YSGP" is the encrypted v3 container (the native loader, {@code natives.LegacyNative}).
 */
public final class LegacyYsm {

    /** Which kind of file. */
    public enum Kind {
        V1_RAW,
        V2_RAW,
        V3_ENCRYPTED,
        UNSUPPORTED
    }

    /** Decodes a v3 file into the {@link LegacyArchive} layout (the native library; a stub in tests). */
    public interface V3Decoder {

        byte[] decode(byte[] file) throws IOException;
    }

    private static final byte[] V3_MAGIC = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P' };

    private LegacyYsm() {}

    public static Kind probe(byte[] file) {
        if (file.length >= 8 && Arrays.equals(Arrays.copyOf(file, 4), LegacyBundle.MAGIC)) {
            int version = (file[4] & 0xFF) << 24 | (file[5] & 0xFF) << 16 | (file[6] & 0xFF) << 8 | file[7] & 0xFF;
            return version == 1 ? Kind.V1_RAW : version == 2 ? Kind.V2_RAW : Kind.UNSUPPORTED;
        }
        return file.length >= V3_MAGIC.length && Arrays.equals(Arrays.copyOf(file, V3_MAGIC.length), V3_MAGIC)
            ? Kind.V3_ENCRYPTED
            : Kind.UNSUPPORTED;
    }

    /**
     * @return the model's files by path, with a {@code ysm.json} (written for the 1.x layout when the bundle has none)
     */
    public static Map<String, byte[]> read(byte[] file, V3Decoder v3, int maxFileBytes, long maxTotalBytes)
        throws IOException {
        Map<String, byte[]> files;
        switch (probe(file)) {
            case V1_RAW:
            case V2_RAW:
                files = LegacyBundle.read(file, maxFileBytes, maxTotalBytes);
                break;
            case V3_ENCRYPTED:
                if (v3 == null) {
                    throw new IOException("encrypted v3 .ysm needs the native loader");
                }
                files = LegacyArchive.parse(v3.decode(file), maxFileBytes).files;
                break;
            default:
                throw new IOException("not a .ysm file this version can read");
        }
        if (LegacyLayout.applies(files)) {
            LegacyLayout.addManifest(files);
        }
        return files;
    }
}

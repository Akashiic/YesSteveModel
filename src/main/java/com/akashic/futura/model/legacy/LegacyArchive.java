package com.akashic.futura.model.legacy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Reads what the native legacy loader returns ({@code futura::legacy::Serialize} in native/src/exporter.cc): "F3DL",
 * format 1, the legacy inner version, then the files of an open-format model. Raw RGBA images are encoded as PNG here.
 */
public final class LegacyArchive {

    static final byte[] MAGIC = { 'F', '3', 'D', 'L' };
    private static final int KIND_BYTES = 0;
    private static final int KIND_RGBA = 1;
    private static final int MAX_FILES = 65536;

    /** The legacy container version of the source ({@code LegacyModel.version}: 1 to 32). */
    public final int innerVersion;
    public final Map<String, byte[]> files;

    private LegacyArchive(int innerVersion, Map<String, byte[]> files) {
        this.innerVersion = innerVersion;
        this.files = files;
    }

    public static LegacyArchive parse(byte[] data, int maxFileBytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("not a legacy export");
        }
        int format = in.readInt();
        if (format != 1) {
            throw new IOException("unknown legacy export format " + format);
        }
        int version = in.readInt();
        int count = in.readInt();
        if (count < 0 || count > MAX_FILES) {
            throw new IOException("legacy export lists " + count + " files");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int kind = in.readUnsignedByte();
            byte[] path = new byte[in.readUnsignedShort()];
            in.readFully(path);
            String name = new String(path, StandardCharsets.UTF_8);
            int width = 0;
            int height = 0;
            if (kind == KIND_RGBA) {
                width = in.readInt();
                height = in.readInt();
            } else if (kind != KIND_BYTES) {
                throw new IOException("unknown legacy file kind " + kind);
            }
            int size = in.readInt();
            if (size < 0 || size > in.available()) {
                throw new IOException("truncated legacy export");
            }
            byte[] content = new byte[size];
            in.readFully(content);
            if (kind == KIND_RGBA) {
                content = png(content, width, height);
            }
            if (content.length > maxFileBytes) {
                throw new IOException(name + " is over " + maxFileBytes + " bytes");
            }
            if (files.put(name, content) != null) {
                throw new IOException("legacy export repeats " + name);
            }
        }
        if (in.available() != 0) {
            throw new IOException("legacy export has trailing bytes");
        }
        return new LegacyArchive(version, files);
    }

    /** Encodes straight (non-premultiplied) RGBA pixels as a PNG. */
    static byte[] png(byte[] rgba, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || (long) width * height * 4 != rgba.length) {
            throw new IOException("legacy RGBA image has inconsistent size");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[width * height];
        for (int i = 0, o = 0; i < argb.length; i++, o += 4) {
            argb[i] = (rgba[o + 3] & 0xFF) << 24 | (rgba[o] & 0xFF) << 16
                | (rgba[o + 1] & 0xFF) << 8
                | rgba[o + 2] & 0xFF;
        }
        image.setRGB(0, 0, width, height, argb, 0, width);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }
}

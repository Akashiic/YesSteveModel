/*
 * Adapted from YesSteveModel-Native (https://github.com/YesSteveModel/YesSteveModel-Native, branch dev, commit
 * 907d1c6), modules/legacy/src/bundle.cc, Apache License 2.0.
 * Changes: Java with the JDK's MD5, AES-CBC, zlib and java.util.Random instead of Crypto++, zlib-ng and the native
 * JavaRandom; reads all entries into memory with limits.
 */
package com.akashic.futura.model.legacy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The raw {@code .ysm} bundles of YSM 1.x and early 2.x ("YSGP", version 1 or 2): a list of the model's own files, each
 * AES-CBC encrypted and zlib compressed. Version 1 stores each file's key; version 2 encrypts the key with one derived
 * from the MD5 of the file through {@link Random}. The files inside are an ordinary model (1.x layout or
 * {@code ysm.json}).
 */
public final class LegacyBundle {

    static final byte[] MAGIC = { 'Y', 'S', 'G', 'P' };
    private static final int HEADER = 24;

    private LegacyBundle() {}

    /**
     * @param maxFileBytes  the largest decompressed file accepted
     * @param maxTotalBytes the largest sum of decompressed files accepted
     * @return the files by their stored name, in stored order
     */
    public static Map<String, byte[]> read(byte[] data, int maxFileBytes, long maxTotalBytes) throws IOException {
        if (data.length < HEADER || !Arrays.equals(Arrays.copyOf(data, 4), MAGIC)) {
            throw new IOException("not a YSGP bundle");
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        in.position(4);
        int version = in.getInt();
        if (version != 1 && version != 2) {
            throw new IOException("unsupported YSGP bundle version " + version);
        }
        byte[] md5 = new byte[16];
        in.get(md5);
        if (!MessageDigest.isEqual(md5, md5(data, HEADER, data.length - HEADER))) {
            throw new IOException("YSGP bundle checksum mismatch");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        while (in.hasRemaining()) {
            String name;
            int size;
            byte[] content;
            if (version == 2) {
                name = new String(base64(bytes(in, length(in))), StandardCharsets.UTF_8);
                size = length(in);
                int passwordSize = length(in);
                byte[] password = bytes(in, passwordSize);
                byte[] iv = bytes(in, 16);
                byte[] cipher = bytes(in, size);
                byte[] passwordKey = md5(cipher, 0, cipher.length);
                new Random(
                    ByteBuffer.wrap(passwordKey, 8, 8)
                        .getLong()).nextBytes(passwordKey);
                byte[] key = Arrays.copyOf(aes(password, passwordKey, iv), 16);
                content = inflate(aes(cipher, key, iv), maxFileBytes);
            } else {
                name = new String(bytes(in, length(in)), StandardCharsets.UTF_8);
                size = length(in);
                byte[] key = bytes(in, 16);
                byte[] iv = bytes(in, 16);
                content = inflate(aes(bytes(in, size), key, iv), maxFileBytes);
            }
            total += content.length;
            if (total > maxTotalBytes) {
                throw new IOException("YSGP bundle holds more than " + maxTotalBytes + " bytes");
            }
            if (files.put(name, content) != null) {
                throw new IOException("YSGP bundle repeats " + name);
            }
        }
        return files;
    }

    private static int length(ByteBuffer in) throws IOException {
        if (in.remaining() < 4) {
            throw new IOException("truncated YSGP bundle");
        }
        int n = in.getInt();
        if (n < 0 || n > in.remaining()) {
            throw new IOException("truncated YSGP bundle");
        }
        return n;
    }

    private static byte[] bytes(ByteBuffer in, int n) throws IOException {
        if (n < 0 || n > in.remaining()) {
            throw new IOException("truncated YSGP bundle");
        }
        byte[] out = new byte[n];
        in.get(out);
        return out;
    }

    private static byte[] base64(byte[] text) throws IOException {
        try {
            return Base64.getDecoder()
                .decode(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("YSGP bundle has an invalid file name", e);
        }
    }

    static byte[] md5(byte[] data, int offset, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data, offset, length);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] aes(byte[] data, byte[] key, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("YSGP bundle entry cannot be decrypted", e);
        }
    }

    static byte[] inflate(byte[] data, int max) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(Math.max(data.length * 4, 64), max));
            byte[] buf = new byte[16384];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IOException("truncated YSGP bundle entry");
                }
                if (out.size() + n > max) {
                    throw new IOException("YSGP bundle entry is over " + max + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("YSGP bundle entry is not zlib data", e);
        } finally {
            inflater.end();
        }
    }
}

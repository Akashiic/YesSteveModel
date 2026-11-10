package com.akashic.futura.model.legacy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Writes YSGP bundles the way the old YSM exporter did (the inverse of bundle.cc), for tests. */
public final class LegacyTestSupport {

    public static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    private LegacyTestSupport() {}

    /** Every file of a built-in model folder, by relative path. */
    public static Map<String, byte[]> builtin(String folder) throws IOException {
        Path root = BUILTIN.resolve(folder);
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) {
                    files.put(
                        root.relativize(p)
                            .toString()
                            .replace('\\', '/'),
                        Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    public static byte[] bundle(int version, Map<String, byte[]> files, long seed) throws IOException {
        Random random = new Random(seed);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            byte[] compressed = deflate(f.getValue());
            byte[] iv = new byte[16];
            byte[] key = new byte[16];
            random.nextBytes(iv);
            random.nextBytes(key);
            byte[] cipher = aes(compressed, key, iv);
            if (version == 1) {
                writeBlock(
                    body,
                    f.getKey()
                        .getBytes(StandardCharsets.UTF_8));
                writeInt(body, cipher.length);
                body.write(key);
                body.write(iv);
                body.write(cipher);
            } else {
                writeBlock(
                    body,
                    Base64.getEncoder()
                        .encode(
                            f.getKey()
                                .getBytes(StandardCharsets.UTF_8)));
                writeInt(body, cipher.length);
                byte[] passwordKey = LegacyBundle.md5(cipher, 0, cipher.length);
                new Random(
                    ByteBuffer.wrap(passwordKey, 8, 8)
                        .getLong()).nextBytes(passwordKey);
                byte[] password = new byte[32]; // the key, then filler, as the exporter padded it
                System.arraycopy(key, 0, password, 0, 16);
                byte[] encryptedPassword = aes(password, passwordKey, iv);
                writeInt(body, encryptedPassword.length);
                body.write(encryptedPassword);
                body.write(iv);
                body.write(cipher);
            }
        }
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(LegacyBundle.MAGIC);
        writeInt(out, version);
        out.write(LegacyBundle.md5(payload, 0, payload.length));
        out.write(payload);
        return out.toByteArray();
    }

    private static void writeBlock(ByteArrayOutputStream out, byte[] data) throws IOException {
        writeInt(out, data.length);
        out.write(data);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] aes(byte[] data, byte[] key, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The synthetic v32 model exported by native/test/native_test.cc (see its TestExport). */
    public static byte[] syntheticExport() throws IOException {
        return Files.readAllBytes(Paths.get("src/test/resources/legacy/synthetic-v32.f3dl"));
    }
}

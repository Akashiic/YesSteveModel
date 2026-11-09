package com.akashic.futura.net;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** A SHA-256 digest, as a value (file hashes and model ids). */
public final class Sha256 implements Comparable<Sha256> {

    public static final int BYTES = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] bytes;

    private Sha256(byte[] bytes) {
        this.bytes = bytes;
    }

    /** Wraps 32 raw bytes (copied). */
    public static Sha256 wrap(byte[] raw) {
        if (raw.length != BYTES) {
            throw new IllegalArgumentException("SHA-256 needs 32 bytes, got " + raw.length);
        }
        return new Sha256(raw.clone());
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No SHA-256 in this JVM", e);
        }
    }

    /** The hash of some bytes. */
    public static Sha256 of(byte[] data) {
        return of(data, 0, data.length);
    }

    public static Sha256 of(byte[] data, int offset, int length) {
        MessageDigest md = digest();
        md.update(data, offset, length);
        return new Sha256(md.digest());
    }

    /** Wraps a finished digest. */
    public static Sha256 of(MessageDigest md) {
        return new Sha256(md.digest());
    }

    public static boolean isHex(String s) {
        if (s == null || s.length() != BYTES * 2) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    /** Parses 64 lowercase hex digits. */
    public static Sha256 fromHex(String hex) {
        if (!isHex(hex)) {
            throw new IllegalArgumentException("Not a SHA-256 in lowercase hex: " + hex);
        }
        byte[] raw = new byte[BYTES];
        for (int i = 0; i < BYTES; i++) {
            raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return new Sha256(raw);
    }

    /** A copy of the 32 raw bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    void writeTo(java.io.DataOutput out) throws java.io.IOException {
        out.write(bytes);
    }

    /** 64 lowercase hex digits. */
    public String hex() {
        char[] out = new char[BYTES * 2];
        for (int i = 0; i < BYTES; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 15];
            out[i * 2 + 1] = HEX[bytes[i] & 15];
        }
        return new String(out);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sha256 && Arrays.equals(bytes, ((Sha256) o).bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public int compareTo(Sha256 o) {
        for (int i = 0; i < BYTES; i++) {
            int c = (bytes[i] & 255) - (o.bytes[i] & 255);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return hex();
    }
}

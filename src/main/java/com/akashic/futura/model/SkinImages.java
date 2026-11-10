package com.akashic.futura.model;

import java.util.UUID;

/**
 * Minecraft player skins for the Steve and Alex models, whose UVs follow the 64x64 skin layout (second layer, separate
 * left arm and leg). Minecraft 1.7.10 only knows the old 64x32 layout and crops what it downloads to it, so the
 * client fetches the skin itself and normalizes it here the way Minecraft 1.8 does: a 64x32 skin gets its left arm
 * and leg mirrored from the right ones and an empty second layer; the base layer is made opaque, and an old skin's
 * hat layer that is fully opaque is cleared (old skins filled it with a colour). No Minecraft classes.
 */
public final class SkinImages {

    public static final int SIZE = 64;

    private SkinImages() {}

    /**
     * @param argb   the downloaded image, row by row, {@code width * height} ARGB pixels
     * @param width  must be 64
     * @param height 64, or 32 for the old layout
     * @return the 64x64 skin, or null if the image is not a skin
     */
    public static int[] normalize(int[] argb, int width, int height) {
        if (width != SIZE || height != SIZE && height != SIZE / 2 || argb == null || argb.length != width * height) {
            return null;
        }
        int[] out = new int[SIZE * SIZE];
        System.arraycopy(argb, 0, out, 0, argb.length);
        boolean legacy = height == SIZE / 2;
        if (legacy) {
            // Minecraft 1.8 ImageBufferDownload: left leg and arm from the right ones, each face mirrored.
            flip(out, 4, 16, 8, 20, 20, 48); // leg top
            flip(out, 8, 16, 12, 20, 24, 48); // leg bottom
            flip(out, 8, 20, 12, 32, 16, 52); // leg right -> left side
            flip(out, 4, 20, 8, 32, 20, 52); // leg front
            flip(out, 0, 20, 4, 32, 24, 52); // leg outer side
            flip(out, 12, 20, 16, 32, 28, 52); // leg back
            flip(out, 44, 16, 48, 20, 36, 48); // arm top
            flip(out, 48, 16, 52, 20, 40, 48); // arm bottom
            flip(out, 48, 20, 52, 32, 32, 52);
            flip(out, 44, 20, 48, 32, 36, 52);
            flip(out, 40, 20, 44, 32, 40, 52);
            flip(out, 52, 20, 56, 32, 44, 52);
        }
        setOpaque(out, 0, 0, 32, 16);
        if (legacy) {
            clearIfOpaque(out, 32, 0, 64, 32);
        }
        setOpaque(out, 0, 16, 64, 32);
        setOpaque(out, 16, 48, 48, 64);
        return out;
    }

    /** Copies the source rectangle to {@code (dx, dy)} mirrored left to right. */
    private static void flip(int[] img, int sx1, int sy1, int sx2, int sy2, int dx, int dy) {
        int w = sx2 - sx1;
        for (int y = sy1; y < sy2; y++) {
            for (int x = sx1; x < sx2; x++) {
                img[(dy + y - sy1) * SIZE + dx + (w - 1 - (x - sx1))] = img[y * SIZE + x];
            }
        }
    }

    private static void setOpaque(int[] img, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                img[y * SIZE + x] |= 0xFF000000;
            }
        }
    }

    /** Clears the area if none of it is transparent already (an old skin's opaque hat layer). */
    private static void clearIfOpaque(int[] img, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                if (img[y * SIZE + x] >>> 24 < 128) {
                    return;
                }
            }
        }
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                img[y * SIZE + x] &= 0x00FFFFFF;
            }
        }
    }

    /** The default skin of a player without one, as Minecraft 1.8 and later choose it: Alex for odd UUID hashes. */
    public static boolean defaultSlim(UUID player) {
        return player != null && (player.hashCode() & 1) == 1;
    }

    /** The {@code model} metadata of a skin texture: {@code slim} is Alex (3-pixel arms), anything else Steve. */
    public static boolean slim(String modelMetadata) {
        return "slim".equals(modelMetadata);
    }
}

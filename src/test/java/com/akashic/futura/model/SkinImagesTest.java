package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class SkinImagesTest {

    private static int pixel(int x, int y) {
        return 0xFF000000 | x << 16 | y << 8 | 0x7F;
    }

    /** A 64x32 skin whose every pixel encodes its own position (opaque), and the hat layer as given. */
    private static int[] legacy(boolean opaqueHat) {
        int[] img = new int[64 * 32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                img[y * 64 + x] = pixel(x, y);
            }
        }
        if (!opaqueHat) {
            img[5 * 64 + 40] = 0; // one transparent hat texel: the hat is a real layer
        }
        return img;
    }

    @Test
    void oldSkinsGetMirroredLeftLimbsAndAnEmptySecondLayer() {
        int[] out = SkinImages.normalize(legacy(false), 64, 32);
        assertNotNull(out);
        assertEquals(64 * 64, out.length);
        // the top half is kept
        assertEquals(pixel(10, 10), out[10 * 64 + 10]);
        // left leg front (20..24, 52..64) = right leg front (4..8, 20..32) mirrored
        assertEquals(pixel(7, 20), out[52 * 64 + 20]);
        assertEquals(pixel(4, 31), out[63 * 64 + 23]);
        // left arm front (36..40, 52..64) = right arm front (44..48, 20..32) mirrored
        assertEquals(pixel(47, 25), out[57 * 64 + 36]);
        // left arm top (36..40, 48..52) = right arm top (44..48, 16..20) mirrored
        assertEquals(pixel(44, 16), out[48 * 64 + 39]);
        // second layer of body, arms and legs stays empty
        assertEquals(0, out[36 * 64 + 20] >>> 24);
        assertEquals(0, out[52 * 64 + 4] >>> 24);
        assertEquals(0, out[52 * 64 + 52] >>> 24);
        // a hat layer with transparency is kept as it is
        assertEquals(pixel(41, 5), out[5 * 64 + 41]);
    }

    @Test
    void anOldFullyOpaqueHatLayerIsCleared() {
        int[] out = SkinImages.normalize(legacy(true), 64, 32);
        assertEquals(0, out[5 * 64 + 41] >>> 24);
        assertEquals(0, out[2 * 64 + 60] >>> 24, "the area the Steve/Alex face details read is cleared too");
        assertEquals(0xFF, out[10 * 64 + 10] >>> 24, "the head stays");
    }

    @Test
    void modernSkinsKeepTheirLayersAndGetAnOpaqueBase() {
        int[] img = new int[64 * 64];
        img[8 * 64 + 8] = 0x00123456; // a transparent texel on the head (base layer)
        img[36 * 64 + 20] = 0x80FFFFFF; // half transparent jacket
        img[2 * 64 + 40] = 0x00FFFFFF; // transparent hat
        int[] out = SkinImages.normalize(img, 64, 64);
        assertEquals(0xFF123456, out[8 * 64 + 8], "base layer made opaque");
        assertEquals(0x80FFFFFF, out[36 * 64 + 20], "second layer keeps its alpha");
        assertEquals(0x00FFFFFF, out[2 * 64 + 40], "a modern hat layer is never cleared");
        assertEquals(0xFF, out[50 * 64 + 20] >>> 24, "the left leg is base layer: opaque");
    }

    @Test
    void refusesWhatIsNotASkin() {
        assertNull(SkinImages.normalize(new int[128 * 128], 128, 128));
        assertNull(SkinImages.normalize(new int[64 * 48], 64, 48));
        assertNull(SkinImages.normalize(new int[10], 64, 64));
    }

    @Test
    void steveOrAlex() {
        assertTrue(SkinImages.slim("slim"));
        assertFalse(SkinImages.slim(null));
        assertFalse(SkinImages.slim("default"));
        // Minecraft 1.8+: Alex for an odd UUID hash
        UUID even = new UUID(0, 0);
        UUID odd = new UUID(0, 1);
        assertFalse(SkinImages.defaultSlim(even));
        assertTrue(SkinImages.defaultSlim(odd));
    }
}

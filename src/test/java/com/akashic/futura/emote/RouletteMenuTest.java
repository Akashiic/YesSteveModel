package com.akashic.futura.emote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.manifest.ModelManifest;
import com.akashic.futura.format.pojo.manifest.settings.ModelProperties;

/** Roulette pages, sub-pages, #return, config buttons and the extra keys, as upstream's roulette behaves. */
class RouletteMenuTest {

    private static ModelProperties properties(String json) {
        return FormatGson.create()
            .fromJson(json, ModelProperties.class);
    }

    private static final String MENU = "{\"extra_animation\":{"
        + "\"extra0\":\"#cfg\",\"wave\":\"Wave\",\"#dance\":\"Dances\",\"extra3\":\"\",\"#return\":\"Back\","
        + "\"a5\":\"5\",\"a6\":\"6\",\"a7\":\"7\",\"a8\":\"8\",\"a9\":\"9\"},"
        + "\"extra_animation_buttons\":[{\"id\":\"cfg\",\"name\":\"Settings\",\"config_forms\":[]}],"
        + "\"extra_animation_classify\":[{\"id\":\"dance\",\"extra_animation\":{"
        + "\"d0\":\"Spin\",\"#deeper\":\"More\",\"#return\":\"Up\"}},"
        + "{\"id\":\"deeper\",\"extra_animation\":{\"#deeper\":\"Again\"}}]}";

    private static String act(RouletteMenu.Action a) {
        return a.toString();
    }

    @Test
    void labelsAndPages() {
        RouletteMenu m = new RouletteMenu(properties(MENU));
        assertFalse(m.isEmpty());
        assertEquals(
            10,
            m.entries()
                .size());
        assertEquals(2, m.pageCount());
        RouletteMenu.Entry first = m.pageEntries()
            .get(0);
        assertEquals("Settings", first.label);
        assertTrue(first.hasConfig());
        assertEquals(
            "3",
            m.pageEntries()
                .get(3).label);
        assertTrue(
            m.pageEntries()
                .get(2)
                .isClassify());
        assertTrue(
            m.pageEntries()
                .get(4)
                .isReturn());
        assertTrue(m.showsKeys());
        m.pageDown();
        assertEquals(1, m.page());
        assertEquals(
            2,
            m.pageEntries()
                .size());
        assertFalse(m.showsKeys());
        m.pageDown();
        assertEquals(1, m.page());
        m.pageUp();
        m.pageUp();
        assertEquals(0, m.page());
    }

    @Test
    void clicks() {
        RouletteMenu m = new RouletteMenu(properties(MENU));
        assertEquals("PLAY extra0", act(m.click(0)));
        assertEquals("CONFIG cfg", act(m.clickConfig(0)));
        assertEquals("NONE", act(m.clickConfig(1)));
        assertEquals("NONE", act(m.click(7 + 1)));
        // #return at the root closes
        assertEquals("CLOSE", act(m.click(4)));
        // a sub-page
        assertEquals("REFRESH", act(m.click(2)));
        assertEquals(Arrays.asList("", "dance"), m.path());
        assertFalse(m.showsKeys());
        assertEquals("PLAY d0", act(m.click(0)));
        assertEquals("REFRESH", act(m.click(2)));
        assertEquals(Arrays.asList(""), m.path());
    }

    @Test
    void pathDepthIsLimited() {
        RouletteMenu m = new RouletteMenu(properties(MENU));
        m.click(2);
        m.click(1);
        for (int i = 0; i < 3; i++) {
            assertEquals("REFRESH", act(m.click(0)));
        }
        assertEquals(
            6,
            m.path()
                .size());
        assertEquals("TOO_DEEP", act(m.click(0)));
        m.back();
        assertEquals(
            5,
            m.path()
                .size());
    }

    @Test
    void extraKeysAddressTheRoot() {
        RouletteMenu m = new RouletteMenu(properties(MENU));
        assertEquals("PLAY extra0", act(m.key(0)));
        assertEquals("PLAY wave", act(m.key(1)));
        assertEquals("STOP", act(m.key(4)));
        assertEquals("NONE", act(m.key(12)));
        assertEquals("REFRESH", act(m.key(2)));
        assertEquals(Arrays.asList("", "dance"), m.path());
        m.reset();
        assertEquals(Arrays.asList(""), m.path());
    }

    @Test
    void defaultModel() throws IOException {
        try (InputStream in = RouletteMenuTest.class.getResourceAsStream("/assets/futura3d/builtin/default/ysm.json");
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            ModelManifest manifest = FormatGson.create()
                .fromJson(reader, ModelManifest.class);
            RouletteMenu m = new RouletteMenu(manifest.properties);
            assertEquals(
                8,
                m.entries()
                    .size());
            RouletteMenu.Entry extra0 = m.entries()
                .get(0);
            assertEquals("extra0", extra0.key);
            assertEquals("0", extra0.label);
            assertTrue(extra0.hasConfig());
            assertEquals(
                "5",
                m.entries()
                    .get(5).label);
            assertNull(
                m.entries()
                    .get(5).config);
            assertEquals("PLAY extra5", act(m.key(5)));
        }
    }

    @Test
    void emptyModel() {
        RouletteMenu m = new RouletteMenu(properties("{}"));
        assertTrue(m.isEmpty());
        assertEquals(1, m.pageCount());
        assertTrue(
            m.pageEntries()
                .isEmpty());
        assertEquals("NONE", act(m.click(0)));
    }
}

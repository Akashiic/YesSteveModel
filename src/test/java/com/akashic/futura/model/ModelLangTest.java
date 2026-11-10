package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** The models' own language files: reading, locales, upstream's fallback order and keys. */
class ModelLangTest {

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    private static ModelSource builtin(String folder) throws IOException {
        return new FolderModelSource(
            BUILTIN.resolve(folder)
                .toFile());
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void mapsTheGameLanguageToTheModelsFileNames() {
        assertEquals("en_us", ModelLang.locale("en_US"));
        assertEquals("pt_br", ModelLang.locale("pt_BR"));
        assertEquals("zh_cn", ModelLang.locale("zh_CN"));
        assertEquals("en_us", ModelLang.locale(null));
        assertEquals("en_us", ModelLang.locale("  "));
    }

    @Test
    void readsTheDefaultModelsLanguages() throws IOException {
        ModelLang lang = ModelLang.read(builtin("default"), null);
        assertEquals(new TreeSet<>(Arrays.asList("en_us", "zh_cn")), lang.locales());
        assertEquals("Moriya Step", lang.translate("en_us", ModelLang.extraAnimation("extra0")));
        assertEquals("Headdress", lang.translate("en_us", ModelLang.configTitle("extra_config", 0)));
        assertEquals("Blue", lang.translate("en_us", ModelLang.texture("blue")));
        String zh = lang.translate("zh_cn", ModelLang.extraAnimation("extra0"));
        assertFalse(zh == null || zh.equals("Moriya Step"), "zh_cn has its own text: " + zh);
    }

    @Test
    void fallsBackToEnglishThenToTheManifest() throws IOException {
        ModelLang lang = ModelLang.read(builtin("wine_fox/14_momo"), "lang");
        // pt_br has no file: en_us; zh_cn has its own; a key nobody has: the ysm.json text.
        assertEquals("MoMo Wine Fox", lang.get("pt_br", ModelLang.NAME, "x"));
        assertEquals("莫莫酒狐", lang.get("zh_cn", ModelLang.NAME, "x"));
        assertEquals("Fox Dance~", lang.get("pt_br", ModelLang.extraAnimation("#H_d_c"), "狐蹈~"));
        assertEquals("ysm", lang.get("pt_br", "properties.nothing", "ysm"));
        assertNull(lang.translate("en_us", "properties.nothing"));
    }

    @Test
    void aKeyMissingInTheLocaleComesFromEnglish() {
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        Map<String, String> en = new LinkedHashMap<>();
        en.put("metadata.name", "Fox");
        en.put("metadata.tips", "Hello");
        Map<String, String> pt = new LinkedHashMap<>();
        pt.put("metadata.name", "Raposa");
        files.put("en_us", en);
        files.put("pt_BR", pt);
        ModelLang lang = ModelLang.of(files);
        assertEquals("Raposa", lang.translate("pt_br", "metadata.name"));
        assertEquals("Hello", lang.translate("pt_br", "metadata.tips"), "upstream ModelInfoView.translate");
        assertEquals("Fox", lang.translate("de_de", "metadata.name"));
    }

    @Test
    void readsFromMemoryWithAnyCaseAPathAndBrokenFiles() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("ysm.json", utf8("{\"spec\":2}"));
        files.put("texts/PT_BR.json", utf8("﻿{\"metadata.name\":\"Raposa\",\"n\":3,\"o\":{\"a\":\"b\"}}"));
        files.put("texts/en_us.json", utf8("{\"metadata.name\":\"Fox\"}"));
        files.put("texts/broken.json", utf8("{\"metadata.name\":"));
        files.put("texts/list.json", utf8("[\"a\"]"));
        files.put("texts/readme.txt", utf8("not a language"));
        files.put("lang/en_us.json", utf8("{\"metadata.name\":\"Other folder\"}"));
        ModelLang lang = ModelLang.read(new MemoryModelSource("mem", files), "texts/");
        assertEquals(new TreeSet<>(Arrays.asList("en_us", "pt_br")), lang.locales());
        assertEquals("Raposa", lang.translate("pt_br", "metadata.name"));
        assertNull(lang.translate("pt_br", "n"), "only string values");
        assertNull(lang.translate("pt_br", "o"));
        assertEquals("Fox", lang.translate("en_us", "metadata.name"));
    }

    @Test
    void aModelWithoutLanguageFilesTranslatesNothing() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("ysm.json", utf8("{\"spec\":2}"));
        ModelLang lang = ModelLang.read(new MemoryModelSource("mem", files), "");
        assertSame(ModelLang.EMPTY, lang);
        assertTrue(lang.isEmpty());
        assertEquals("ysm", lang.get("en_us", ModelLang.NAME, "ysm"));
        assertSame(ModelLang.EMPTY, ModelLang.readQuietly(new MemoryModelSource("mem", files), "../escape"));
    }

    @Test
    void theNameIsNeverBlank() {
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        Map<String, String> en = new LinkedHashMap<>();
        en.put("metadata.name", " ");
        files.put("en_us", en);
        ModelLang lang = ModelLang.of(files);
        assertEquals(" ", lang.get("en_us", ModelLang.NAME, "Fox"));
        assertEquals("Fox", lang.nonBlank("en_us", ModelLang.NAME, "Fox"));
    }

    @Test
    void usesUpstreamsKeys() {
        assertEquals("metadata.authors.2.name", ModelLang.authorName(2));
        assertEquals("properties.extra_animation.wave", ModelLang.extraAnimation("wave"));
        assertEquals("properties.extra_animation.wave.desc", ModelLang.extraAnimationDesc("wave"));
        assertEquals("properties.extra_animation_buttons.cfg.config_forms.1.title", ModelLang.configTitle("cfg", 1));
        assertEquals(
            "properties.extra_animation_buttons.cfg.config_forms.1.description",
            ModelLang.configDescription("cfg", 1));
        assertEquals(
            "properties.extra_animation_buttons.cfg.config_forms.1.labels.0",
            ModelLang.configLabel("cfg", 1, 0));
        assertEquals("files.player.texture.blue", ModelLang.texture("blue"));
    }

    @Test
    void theInfoTranslatesNameTipsAuthorsAndTextures() throws IOException {
        ModelInfo momo = ModelInfo.read(builtin("wine_fox/14_momo"));
        assertEquals("MoMo Wine Fox", momo.name("en_us"));
        assertEquals("MoMo Wine Fox", momo.name("pt_br"));
        assertEquals("莫莫酒狐", momo.name("zh_cn"));
        assertEquals("Welcome to the fox's world~", momo.tips("en_us"));
        assertEquals(
            "ZaiLaiJiJinXiaoMoMo",
            momo.authors("en_us")
                .get(0));
        assertEquals(
            "再来几斤小莫莫",
            momo.authors("zh_cn")
                .get(0));
        ModelInfo def = ModelInfo.read(builtin("default"));
        assertEquals("Blue", def.textureName("blue", "pt_br"));
        assertEquals("nothing", def.textureName("nothing", "pt_br"));
        assertEquals("Default Model", def.tips("en_us"));
        ModelInfo bare = ModelInfo.of(manifestOf("default"), "x");
        assertSame(ModelLang.EMPTY, bare.lang, "the manifest alone has no translations");
        assertEquals(bare.tips, bare.tips("en_us"));
        assertEquals("Default", bare.name("zh_cn"));
    }

    private static com.akashic.futura.format.pojo.manifest.ModelManifest manifestOf(String folder) throws IOException {
        try (java.io.InputStream in = builtin(folder).open("ysm.json")) {
            com.akashic.futura.format.pojo.manifest.ModelManifest m = com.akashic.futura.format.FormatGson.create()
                .fromJson(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8),
                    com.akashic.futura.format.pojo.manifest.ModelManifest.class);
            m.normalize();
            return m;
        }
    }

    @Test
    void everyBuiltinModelHasEnglishAndChineseNames() throws IOException {
        for (String folder : BuiltinIndex.read()) {
            ModelInfo info = ModelInfo.read(builtin(folder));
            assertTrue(
                info.lang.locales()
                    .contains("en_us"),
                folder);
            assertFalse(
                info.name("en_us")
                    .trim()
                    .isEmpty(),
                folder);
            assertFalse(
                info.name("zh_cn")
                    .trim()
                    .isEmpty(),
                folder);
        }
    }
}

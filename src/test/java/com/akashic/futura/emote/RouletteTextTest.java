package com.akashic.futura.emote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.manifest.ModelManifest;
import com.akashic.futura.format.pojo.manifest.settings.ConfigForms;
import com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton;
import com.akashic.futura.format.pojo.manifest.settings.ModelProperties;
import com.akashic.futura.model.FolderModelSource;
import com.akashic.futura.model.ModelInfo;
import com.akashic.futura.model.ModelLang;

/** The roulette's labels, tooltips, sub-page names and form texts from the models' lang files, as upstream. */
class RouletteTextTest {

    private static ModelProperties properties(String folder) throws IOException {
        java.io.File file = new java.io.File("src/main/resources/assets/futura3d/builtin/" + folder + "/ysm.json");
        try (java.io.Reader in = new java.io.InputStreamReader(
            new java.io.FileInputStream(file),
            java.nio.charset.StandardCharsets.UTF_8)) {
            ModelManifest manifest = FormatGson.create()
                .fromJson(in, ModelManifest.class);
            manifest.normalize();
            return manifest.properties;
        }
    }

    private static ModelLang lang(String folder) throws IOException {
        return ModelInfo
            .read(new FolderModelSource(new java.io.File("src/main/resources/assets/futura3d/builtin/" + folder))).lang;
    }

    @Test
    void theDefaultModelsLabelsInEachLanguage() throws IOException {
        RouletteMenu menu = new RouletteMenu(properties("default"));
        ModelLang lang = lang("default");
        List<RouletteMenu.Entry> entries = menu.pageEntries();
        RouletteText en = new RouletteText(lang, "en_us");
        RouletteText pt = new RouletteText(lang, "pt_br");
        RouletteText none = new RouletteText(ModelLang.EMPTY, "en_us");
        // extra0 is the config button "0": translated as properties.extra_animation.extra0
        assertEquals("Moriya Step", en.label(entries.get(0)));
        assertEquals("0", none.label(entries.get(0)), "without lang files: the button's name");
        assertEquals("Wave", pt.label(entries.get(1)), "no pt_br file: English");
        assertEquals("1", none.label(entries.get(1)), "blank value: the index");
        assertNull(en.description(entries.get(1)), "no .desc key: no tooltip");
    }

    @Test
    void momosSubPageFormsAndChinese() throws IOException {
        ModelProperties props = properties("wine_fox/14_momo");
        RouletteMenu menu = new RouletteMenu(props);
        ModelLang lang = lang("wine_fox/14_momo");
        RouletteText en = new RouletteText(lang, "en_us");
        RouletteText zh = new RouletteText(lang, "zh_cn");
        RouletteText none = new RouletteText(null, null);
        assertEquals("Fox Dance~", en.pageName(menu, "H_d_c"));
        assertEquals("狐蹈~", zh.pageName(menu, "H_d_c"));
        assertEquals("狐蹈~", none.pageName(menu, "H_d_c"), "the #id entry's value");
        assertEquals("", en.pageName(menu, ""));
        assertEquals("nowhere", en.pageName(menu, "nowhere"));
        ExtraAnimationButton button = props.extraAnimationButtons.get(0);
        int[] idx = RouletteText.formIndices(button.configForms);
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, idx);
        assertEquals("Fox Size: ", en.formTitle(button, idx[0], button.configForms.get(0)));
        assertEquals("Change the fox size!", en.formDescription(button, idx[0], button.configForms.get(0)));
        assertEquals("狐狐大小变：", zh.formTitle(button, idx[0], button.configForms.get(0)));
        assertEquals("狐狐大小变！", none.formTitle(button, idx[0], button.configForms.get(0)));
        // Upstream's label key has the form index; Momo's file leaves it out, so the manifest's label shows.
        assertEquals("x", en.formLabel(button, 0, 0, "x"));
    }

    @Test
    void descriptionsLabelsAndTheFormCountOfUpstream() {
        ModelProperties props = FormatGson.create()
            .fromJson(
                "{\"extra_animation\":{\"wave\":\"Wave\",\"#more\":\"More\",\"cfg\":\"#b\"},"
                    + "\"extra_animation_classify\":[{\"id\":\"more\",\"extra_animation\":{\"spin\":\"Spin\"}}],"
                    + "\"extra_animation_buttons\":[{\"id\":\"b\",\"name\":\"Settings\",\"config_forms\":["
                    + "{\"type\":\"checkbox\",\"title\":\"A\"},{\"type\":\"text\",\"title\":\"Note\"},"
                    + "{\"type\":\"radio\",\"title\":\"R\",\"description\":\"d\",\"labels\":{\"One\":\"\",\"Two\":\"\"}}]}]}",
                ModelProperties.class);
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        Map<String, String> pt = new LinkedHashMap<>();
        pt.put("properties.extra_animation.wave", "Acenar");
        pt.put("properties.extra_animation.wave.desc", "Acena com a mão");
        pt.put("properties.extra_animation.spin.desc", " ");
        pt.put("properties.extra_animation.#more", "Mais");
        pt.put("properties.extra_animation.cfg", "Ajustes");
        pt.put("properties.extra_animation_buttons.b.config_forms.1.title", "Escolha");
        pt.put("properties.extra_animation_buttons.b.config_forms.1.labels.1", "Dois");
        files.put("pt_br", pt);
        RouletteText text = new RouletteText(ModelLang.of(files), "pt_br");
        RouletteMenu menu = new RouletteMenu(props);
        List<RouletteMenu.Entry> root = menu.pageEntries();
        assertEquals("Acenar", text.label(root.get(0)));
        assertEquals("Acena com a mão", text.description(root.get(0)));
        assertEquals("Mais", text.label(root.get(1)));
        assertEquals("Mais", text.pageName(menu, "more"));
        assertEquals("Ajustes", text.label(root.get(2)));
        menu.click(1);
        assertNull(
            text.description(
                menu.pageEntries()
                    .get(0)),
            "a blank description: no tooltip");
        ExtraAnimationButton b = props.extraAnimationButtons.get(0);
        int[] idx = RouletteText.formIndices(b.configForms);
        // upstream counts only checkbox, range and radio: the text form has no index, the radio is form 1
        assertArrayEquals(new int[] { 0, -1, 1 }, idx);
        ConfigForms radio = b.configForms.get(2);
        assertEquals("Escolha", text.formTitle(b, idx[2], radio));
        assertEquals("d", text.formDescription(b, idx[2], radio));
        assertEquals("One", text.formLabel(b, idx[2], 0, "One"));
        assertEquals("Dois", text.formLabel(b, idx[2], 1, "Two"));
        assertEquals("Note", text.formTitle(b, idx[1], b.configForms.get(1)));
    }
}

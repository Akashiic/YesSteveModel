/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the translated texts of src/main/java/com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java (drawRouletteText,
 * drawTooltips, addConfigForms, addRatioButtons), Apache License 2.0.
 * Changes: no Minecraft classes; the locale is passed in; sub-page names on the path line (upstream has no path line).
 */
package com.akashic.futura.emote;

import java.util.List;

import com.akashic.futura.format.pojo.manifest.settings.ConfigForms;
import com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton;
import com.akashic.futura.model.ModelLang;

/**
 * The roulette's texts in the player's language, from the model's {@code lang/} files ({@link ModelLang}), with the
 * {@code ysm.json} text as the fallback, as upstream looks them up. No Minecraft classes.
 */
public final class RouletteText {

    private final ModelLang lang;
    private final String locale;

    /**
     * @param lang   the model's translations ({@link ModelLang#EMPTY} for none)
     * @param locale a model locale ({@link ModelLang#locale(String)})
     */
    public RouletteText(ModelLang lang, String locale) {
        this.lang = lang == null ? ModelLang.EMPTY : lang;
        this.locale = locale == null ? ModelLang.DEFAULT_LOCALE : locale;
    }

    /**
     * A slice's label: {@code properties.extra_animation.<key>}, else the value, the config button's name, or the
     * index when those are blank (upstream's {@code drawRouletteText}).
     */
    public String label(RouletteMenu.Entry entry) {
        return lang.get(locale, ModelLang.extraAnimation(entry.key), entry.label);
    }

    /** A slice's tooltip ({@code properties.extra_animation.<key>.desc}), or null when blank or missing. */
    public String description(RouletteMenu.Entry entry) {
        String desc = lang.translate(locale, ModelLang.extraAnimationDesc(entry.key));
        return desc == null || desc.trim()
            .isEmpty() ? null : desc;
    }

    /**
     * The name of a sub-page on the path line: the label of its {@code #id} entry, translated; the root (empty id)
     * stays empty.
     */
    public String pageName(RouletteMenu menu, String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String key = RouletteMenu.SPEC_PREFIX + id;
        return lang.nonBlank(locale, ModelLang.extraAnimation(key), menu.classifyLabel(id));
    }

    /**
     * The index of each form in the translation keys: upstream counts only the forms it shows (checkbox, range,
     * radio), the same index its config route uses; other types get -1.
     */
    public static int[] formIndices(List<ConfigForms> forms) {
        int[] out = new int[forms == null ? 0 : forms.size()];
        int next = 0;
        for (int i = 0; i < out.length; i++) {
            ConfigForms form = forms.get(i);
            out[i] = form != null && ConfigForm.type(form) != ConfigForm.Type.OTHER ? next++ : -1;
        }
        return out;
    }

    /** A form's title ({@code ...config_forms.<n>.title}), else the manifest's. */
    public String formTitle(ExtraAnimationButton button, int index, ConfigForms form) {
        String fallback = form.title == null ? "" : form.title;
        return index < 0 ? fallback : lang.get(locale, ModelLang.configTitle(button.id, index), fallback);
    }

    /** A form's description ({@code ...config_forms.<n>.description}), else the manifest's. */
    public String formDescription(ExtraAnimationButton button, int index, ConfigForms form) {
        String fallback = form.description == null ? "" : form.description;
        return index < 0 ? fallback : lang.get(locale, ModelLang.configDescription(button.id, index), fallback);
    }

    /** A radio label ({@code ...config_forms.<n>.labels.<m>}), else the manifest's. */
    public String formLabel(ExtraAnimationButton button, int index, int label, String fallback) {
        return index < 0 ? fallback : lang.get(locale, ModelLang.configLabel(button.id, index, label), fallback);
    }
}

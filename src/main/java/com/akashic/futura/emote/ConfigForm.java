/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the config form semantics of src/main/java/com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java
 * (addConfigForms, addRatioButtons), src/main/java/com/elfmcys/ysm/client/gui/button/FlatSlider.java (step and range)
 * and src/main/java/com/elfmcys/ysm/expression/aot/EntityExpressionRuntime.java (readConfigBoolean/Float,
 * writeConfigBoolean/Float, executeConfigLabel), Apache License 2.0.
 * Changes: evaluated with the local animator's Molang context instead of compiled config programs; no Minecraft
 * classes; nothing goes over the network here (roaming variables written by a form are synced by the client network
 * like any other change of them; other variables stay local).
 */
package com.akashic.futura.emote;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.akashic.futura.format.pojo.manifest.settings.ConfigForms;
import com.akashic.futura.molang.Molang;
import com.akashic.futura.molang.MolangContext;
import com.akashic.futura.molang.MolangValue;

/**
 * What the forms of an {@code extra_animation_buttons} entry read and write. A form's {@code value} is a Molang
 * l-value of the player's variables ({@code v.player_size}, {@code v.roaming.back}); a checkbox reads it as a boolean
 * and writes 1 or 0, a range reads a number and writes it snapped to {@code step} between {@code min} and {@code max},
 * and a radio reads the index of the selected label and, on a click, runs that label's Molang action
 * ({@code "v.roaming.eye=2"}). The model's own Molang, run locally: never sent anywhere.
 */
public final class ConfigForm {

    public enum Type {

        CHECKBOX,
        RANGE,
        RADIO,
        /** Another type: shown as text, not editable. */
        OTHER
    }

    /** The variable a write goes through: one compiled program per form, whatever the value. */
    static final String SCRATCH = "futura3d_config_value";

    /** {@code v.x}, {@code variable.a.b}, {@code v.roaming.x}, {@code r.x}, {@code roaming.x}: variables only. */
    private static final Pattern LVALUE = Pattern
        .compile("\\s*(?i:v|variable|r|roaming)(\\.[A-Za-z_][A-Za-z0-9_]*)+\\s*");

    private ConfigForm() {}

    public static Type type(ConfigForms form) {
        String t = form.type == null ? ""
            : form.type.trim()
                .toLowerCase(Locale.ROOT);
        switch (t) {
            case "checkbox":
                return Type.CHECKBOX;
            case "range":
                return Type.RANGE;
            case "radio":
                return Type.RADIO;
            default:
                return Type.OTHER;
        }
    }

    /** Whether the form's {@code value} is a variable a form may write. */
    public static boolean writable(ConfigForms form) {
        return form.value != null && LVALUE.matcher(form.value)
            .matches();
    }

    /** A checkbox's state: its value as a boolean (missing: false). */
    public static boolean readBool(ConfigForms form, MolangContext context) {
        return form.value != null && !form.value.trim()
            .isEmpty()
            && Molang.parse(form.value)
                .evaluate(context)
                .asBoolean();
    }

    /** A range's (or radio's) number (missing: 0). */
    public static float readFloat(ConfigForms form, MolangContext context) {
        if (form.value == null || form.value.trim()
            .isEmpty()) {
            return 0;
        }
        double v = Molang.parse(form.value)
            .evalNumber(context);
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : (float) v;
    }

    public static void writeBool(ConfigForms form, MolangContext context, boolean value) {
        assign(form, context, value ? 1 : 0);
    }

    /** Writes a range's value, snapped ({@link #snap}). */
    public static float writeFloat(ConfigForms form, MolangContext context, double value) {
        float v = snap(form, value);
        assign(form, context, v);
        return v;
    }

    /** {@code min} and {@code max} in order (a model may give them reversed). */
    public static double min(ConfigForms form) {
        return Math.min(form.min, form.max);
    }

    public static double max(ConfigForms form) {
        return Math.max(form.min, form.max);
    }

    /** A value clamped to the range and rounded to the nearest {@code step} from {@code min} (no step: unrounded). */
    public static float snap(ConfigForms form, double value) {
        double lo = min(form), hi = max(form);
        if (Double.isNaN(value)) {
            value = lo;
        }
        double v = Math.max(lo, Math.min(hi, value));
        double step = form.step;
        if (step > 0 && !Double.isInfinite(step)) {
            v = lo + Math.round((v - lo) / step) * step;
            v = Math.max(lo, Math.min(hi, v));
            // tidy binary noise: 0.2 * 3 is 0.6000000000000001
            v = Math.round(v * 1e6) / 1e6;
        }
        return (float) v;
    }

    /** Where a value sits between {@code min} and {@code max}, 0 to 1 (a slider's knob). */
    public static float fraction(ConfigForms form, double value) {
        double lo = min(form), hi = max(form);
        return hi <= lo ? 0 : (float) Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
    }

    /** The value at a fraction of the range, snapped. */
    public static float atFraction(ConfigForms form, double fraction) {
        double lo = min(form), hi = max(form);
        return snap(form, lo + Math.max(0, Math.min(1, fraction)) * (hi - lo));
    }

    /** A radio's labels, in the model's order. */
    public static List<String> labels(ConfigForms form) {
        return form.labels == null ? new ArrayList<>() : new ArrayList<>(form.labels.keySet());
    }

    /** A radio's selected label: its value rounded, or 0 when out of range (as upstream). */
    public static int radioSelected(ConfigForms form, MolangContext context) {
        int size = form.labels == null ? 0 : form.labels.size();
        int selected = Math.round(readFloat(form, context));
        return selected < 0 || selected >= size ? 0 : selected;
    }

    /**
     * A click on a radio's label: runs the label's action. A label without one (blank) writes its index to the
     * value, so a radio still works when the model leaves the actions out.
     *
     * @return false when there is no such label
     */
    public static boolean clickLabel(ConfigForms form, MolangContext context, int index) {
        if (form.labels == null || index < 0 || index >= form.labels.size()) {
            return false;
        }
        int i = 0;
        for (Map.Entry<String, String> e : form.labels.entrySet()) {
            if (i++ == index) {
                String action = e.getValue();
                if (action == null || action.trim()
                    .isEmpty()) {
                    assign(form, context, index);
                } else {
                    Molang.parse(action)
                        .evaluate(context);
                }
                return true;
            }
        }
        return false;
    }

    /** {@code value = n}, through one program per l-value. */
    static void assign(ConfigForms form, MolangContext context, double number) {
        if (!writable(form) || Double.isNaN(number) || Double.isInfinite(number)) {
            return;
        }
        context.variables()
            .set(SCRATCH, MolangValue.of(number));
        try {
            Molang.parse(form.value.trim() + " = v." + SCRATCH)
                .evaluate(context);
        } finally {
            context.variables()
                .remove(SCRATCH);
        }
    }

    /** A range's value as shown: up to two decimals, no trailing zeros. */
    public static String format(double value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "")
                .replaceAll("\\.$", "");
        }
        return s.equals("-0") ? "0" : s;
    }
}

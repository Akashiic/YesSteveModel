package com.akashic.futura.emote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akashic.futura.format.pojo.manifest.settings.ConfigForms;
import com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.molang.MolangContext;
import com.akashic.futura.molang.MolangValue;
import com.akashic.futura.molang.MolangVariables;

/** The config forms read and write the player's variables; roaming ones end up where the network reads them. */
class ConfigFormTest {

    static final class Context implements MolangContext {

        final MolangVariables variables = new MolangVariables();

        @Override
        public MolangValue call(String namespace, String name, MolangValue[] args) {
            return null;
        }

        @Override
        public MolangVariables variables() {
            return variables;
        }
    }

    private static ConfigForms form(String type, String value) {
        ConfigForms f = new ConfigForms();
        f.type = type;
        f.value = value;
        return f;
    }

    @Test
    void checkboxWritesOneOrZero() {
        Context c = new Context();
        ConfigForms f = form("checkbox", "v.roaming.back");
        assertEquals(ConfigForm.Type.CHECKBOX, ConfigForm.type(f));
        assertFalse(ConfigForm.readBool(f, c));
        ConfigForm.writeBool(f, c, true);
        assertTrue(ConfigForm.readBool(f, c));
        assertEquals(
            1,
            c.variables.get("roaming.back")
                .asNumber());
        Map<String, Float> roaming = new HashMap<>();
        c.variables.roamingNumbers(roaming);
        assertEquals(1f, roaming.get("back"), "where the client network reads roaming variables");
        ConfigForm.writeBool(f, c, false);
        assertEquals(
            0,
            c.variables.get("roaming.back")
                .asNumber());
        assertFalse(c.variables.has(ConfigForm.SCRATCH), "the scratch variable is gone");
    }

    @Test
    void rangeSnapsToTheStep() {
        Context c = new Context();
        ConfigForms f = form("range", "v.player_size");
        f.step = 0.2;
        f.min = 0.2;
        f.max = 5;
        assertEquals(0.6f, ConfigForm.writeFloat(f, c, 0.65), 1e-6);
        assertEquals(0.6f, ConfigForm.readFloat(f, c), 1e-6);
        assertEquals(5f, ConfigForm.writeFloat(f, c, 99));
        assertEquals(0.2f, ConfigForm.writeFloat(f, c, -3));
        assertEquals(0f, ConfigForm.fraction(f, 0.2));
        assertEquals(1f, ConfigForm.fraction(f, 5));
        assertEquals(2.6f, ConfigForm.atFraction(f, 0.5), 1e-6);
        assertEquals("0.6", ConfigForm.format(0.6000000001));
        assertEquals("5", ConfigForm.format(5));
        // reversed bounds, no step
        ConfigForms g = form("range", "v.x");
        g.min = 10;
        g.max = 0;
        g.step = 0;
        assertEquals(3.3f, ConfigForm.snap(g, 3.3), 1e-6);
        assertEquals(10f, ConfigForm.snap(g, 30));
    }

    @Test
    void radioRunsTheLabelAction() {
        Context c = new Context();
        ConfigForms f = form("radio", "v.roaming.eye");
        f.labels.put("normal", "v.roaming.eye=0");
        f.labels.put("closed", "v.roaming.eye=1; v.other=7");
        f.labels.put("blank", "");
        assertEquals(0, ConfigForm.radioSelected(f, c));
        assertTrue(ConfigForm.clickLabel(f, c, 1));
        assertEquals(1, ConfigForm.radioSelected(f, c));
        assertEquals(
            7,
            c.variables.get("other")
                .asNumber());
        assertTrue(ConfigForm.clickLabel(f, c, 2), "a label without an action writes its index");
        assertEquals(2, ConfigForm.radioSelected(f, c));
        assertFalse(ConfigForm.clickLabel(f, c, 3));
        c.variables.set("roaming.eye", MolangValue.of(9));
        assertEquals(0, ConfigForm.radioSelected(f, c), "out of range: the first");
    }

    @Test
    void onlyVariablesAreWritten() {
        Context c = new Context();
        assertTrue(ConfigForm.writable(form("checkbox", "v.a")));
        assertTrue(ConfigForm.writable(form("checkbox", " variable.a.b ")));
        assertTrue(ConfigForm.writable(form("checkbox", "r.x")));
        assertFalse(ConfigForm.writable(form("checkbox", "q.health")));
        assertFalse(ConfigForm.writable(form("checkbox", "v.a = 3")));
        assertFalse(ConfigForm.writable(form("checkbox", "")));
        ConfigForms query = form("range", "q.health");
        ConfigForm.writeFloat(query, c, 3);
        assertFalse(c.variables.has(ConfigForm.SCRATCH));
        assertEquals(ConfigForm.Type.OTHER, ConfigForm.type(form("slider", "v.a")));
    }

    @Test
    void builtInFormsWork() throws IOException {
        // wine_fox/14_momo: two ranges and two checkboxes; wine_fox/08_sta: radios with label actions
        for (String folder : new String[] { "wine_fox/14_momo", "wine_fox/08_sta" }) {
            LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + folder + "/"));
            int forms = 0;
            for (ExtraAnimationButton button : model.manifest.properties.extraAnimationButtons) {
                for (ConfigForms f : button.configForms) {
                    Context c = new Context();
                    switch (ConfigForm.type(f)) {
                        case CHECKBOX:
                            assertTrue(ConfigForm.writable(f), f.value);
                            ConfigForm.writeBool(f, c, true);
                            assertTrue(ConfigForm.readBool(f, c), f.value);
                            break;
                        case RANGE:
                            assertTrue(ConfigForm.writable(f), f.value);
                            float v = ConfigForm.writeFloat(f, c, ConfigForm.max(f));
                            assertEquals(v, ConfigForm.readFloat(f, c), 1e-5, f.value);
                            break;
                        case RADIO:
                            int last = f.labels.size() - 1;
                            assertTrue(ConfigForm.clickLabel(f, c, last));
                            break;
                        default:
                            break;
                    }
                    forms++;
                }
            }
            assertTrue(forms > 3, folder + ": " + forms);
        }
    }
}

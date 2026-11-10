package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

/** SlashBlade on the models from external states: names, the main retry, the combo on the swing controller. */
class SlashBladeStatesTest {

    private static final UUID PLAYER = new UUID(1, 1);

    private static PlayerAnimator animator(String builtin) throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + builtin + "/"));
        return new PlayerAnimator(ModelAnimations.compile(model));
    }

    private static void run(PlayerAnimator a, PlayerSnapshot s, int from, int to) {
        for (int t = from; t <= to; t++) {
            s.ticksExisted = t;
            a.update(s, t);
        }
    }

    @Test
    void comboNames() {
        assertEquals("slashblade:combo_a1", SlashBladeStates.comboAnimation("combo_a1"));
        assertEquals("slashblade:combo_a1", SlashBladeStates.comboAnimation(" slashblade:Combo_A1 "));
        assertEquals("slashblade:combo_a4ex", SlashBladeStates.comboAnimation("combo_a4_ex"), "upstream's name fix");
        assertEquals("slashblade_addon:fire_spiral", SlashBladeStates.comboAnimation("slashblade_addon:fire_spiral"));
        assertNull(SlashBladeStates.comboAnimation(""));
        assertNull(SlashBladeStates.comboAnimation(null));
    }

    @Test
    void externalStatesCountChanges() {
        ExternalStates states = new ExternalStates();
        assertTrue(states.set(PLAYER, "k", "a"));
        ExternalStates.Entry e = states.of(PLAYER);
        assertEquals("a", e.get("k"));
        assertEquals(1, e.generation("k"));
        states.set(PLAYER, "k", "a");
        assertEquals(1, e.generation("k"), "the same value: no change");
        states.set(PLAYER, "k", null);
        states.set(PLAYER, "k", "a");
        assertEquals(3, e.generation("k"), "cleared and set again: new");
        // limits
        StringBuilder longKey = new StringBuilder();
        for (int i = 0; i <= ExternalStates.MAX_KEY; i++) {
            longKey.append('k');
        }
        assertFalse(states.set(PLAYER, longKey.toString(), "x"));
        for (int i = 0; i < ExternalStates.MAX_KEYS + 4; i++) {
            states.set(PLAYER, "key" + i, "x");
        }
        assertFalse(states.set(PLAYER, "one_more", "x"));
        assertNull(states.get(PLAYER, "one_more"));
        assertTrue(states.set(PLAYER, "key0", null), "clearing always works");
        for (int i = 0; i < ExternalStates.MAX_PLAYERS + 5; i++) {
            states.set(new UUID(2, i), "k", "v");
        }
        assertEquals(ExternalStates.MAX_PLAYERS, states.players());
        states.clear();
        assertNull(states.of(PLAYER));
    }

    @Test
    void theModelPlaysTheBladeAnimations() throws IOException {
        PlayerAnimator a = animator("wine_fox/13_matured");
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        s.mainhand = ItemInfo.of("minecraft:golden_sword", "sword", ItemInfo.UseAction.BLOCK);
        run(a, s, 0, 10);
        assertEquals("idle", a.playing("player.main"));
        assertEquals("hold_mainhand:sword", a.playing("player.hold_mainhand"));

        // a mod says the sword is a blade (the adapter gives it the slashblade class): the slashblade set
        states.set(PLAYER, SlashBladeStates.HELD, "mainhand");
        s.external = states.of(PLAYER);
        s.mainhand = ItemInfo.of("minecraft:golden_sword", "slashblade", ItemInfo.UseAction.BLOCK);
        run(a, s, 11, 20);
        assertEquals("slashblade:idle", a.playing("player.main"));
        assertEquals("hold_mainhand:slashblade", a.playing("player.hold_mainhand"));
        s.limbSwingAmount = 0.6f;
        run(a, s, 21, 25);
        assertEquals("slashblade:walk", a.playing("player.main"));
        s.limbSwingAmount = 0;

        // a combo: once on the swing controller, the plain swing kept out
        states.set(PLAYER, SlashBladeStates.COMBO, "combo_a1");
        s.swinging = true;
        s.swingTime = 0;
        run(a, s, 26, 30);
        assertEquals("slashblade:combo_a1", a.playing("player.swing"));
        s.swinging = false;
        // the clip (1 s) ends before the chain (41 frames at 30 per second): it is not started again
        run(a, s, 31, 52);
        String after = a.playing("player.swing");
        assertTrue(after == null || after.equals("slashblade:combo_a1"), "not restarted: " + after);
        // cleared and set again: started again
        states.set(PLAYER, SlashBladeStates.COMBO, null);
        run(a, s, 53, 54);
        states.set(PLAYER, SlashBladeStates.COMBO, "combo_a1");
        run(a, s, 55, 56);
        assertEquals("slashblade:combo_a1", a.playing("player.swing"));
        // another combo replaces it at once
        states.set(PLAYER, SlashBladeStates.COMBO, "aerial_rave_a1");
        run(a, s, 57, 58);
        assertEquals("slashblade:aerial_rave_a1", a.playing("player.swing"));
        // a combo the model lacks: nothing new starts
        states.set(PLAYER, SlashBladeStates.COMBO, "no_such_combo");
        s.swinging = true;
        s.swingTime = 0;
        run(a, s, 59, 60);
        assertFalse("swing:slashblade".equals(a.playing("player.swing")), "the plain swing stays out");
        // Molang sees the combo
        states.set(PLAYER, SlashBladeStates.COMBO, "combo_b1");
        assertEquals(
            "slashblade:combo_b1",
            a.context()
                .call("ctrl", "slashblade_animation", new com.akashic.futura.molang.MolangValue[0])
                .asString());
    }

    @Test
    void everyModelFallsBackToTheDefaultSet() throws IOException {
        // misc/1_alex has no slashblade set: the default model's clips play
        PlayerAnimator a = animator("misc/1_alex");
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        states.set(PLAYER, SlashBladeStates.HELD, "mainhand");
        states.set(PLAYER, SlashBladeStates.COMBO, "combo_a1");
        s.external = states.of(PLAYER);
        s.mainhand = ItemInfo.of("minecraft:golden_sword", "slashblade", ItemInfo.UseAction.BLOCK);
        run(a, s, 0, 5);
        assertNotNull(a.model().library.get("slashblade:combo_a1"));
        assertEquals("slashblade:idle", a.playing("player.main"));
        assertEquals("slashblade:combo_a1", a.playing("player.swing"));
        // without the held state the external combo is ignored
        states.set(PLAYER, SlashBladeStates.HELD, null);
        s.mainhand = ItemInfo.of("minecraft:golden_sword", "sword", ItemInfo.UseAction.BLOCK);
        run(a, s, 6, 30);
        assertEquals("idle", a.playing("player.main"));
    }
}

package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akashic.futura.anim.FishingStates.Phase;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.molang.MolangValue;

/** The rod on the models: names, the phase machine, and the default model's fishing set playing. */
class FishingStatesTest {

    private static final UUID PLAYER = new UUID(3, 3);
    private static final ItemInfo ROD = ItemInfo.of("minecraft:fishing_rod", "fishing_rod", ItemInfo.UseAction.NONE);
    private static final ItemInfo SWORD = ItemInfo.of("minecraft:iron_sword", "sword", ItemInfo.UseAction.BLOCK);

    private static PlayerAnimator animator(String builtin) throws IOException {
        LoadedModel model = ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + builtin + "/"));
        return new PlayerAnimator(ModelAnimations.compile(model));
    }

    private static void run(PlayerAnimator a, PlayerSnapshot s, int from, int to) {
        for (int t = from; t <= to; t++) {
            s.ticksExisted = t;
            a.update(s, t);
            s.swingTime++; // a swing lasts a few ticks; the first one is swingTime 0
            if (s.swingTime > 6) {
                s.swinging = false;
            }
        }
    }

    private static void swing(PlayerSnapshot s) {
        s.swinging = true;
        s.swingTime = 0;
    }

    private static AnimationLibrary defaultLibrary() {
        AnimationLibrary library = DefaultAnimations.library();
        assertNotNull(library, "the default model loads");
        return library;
    }

    @Test
    void stateNames() {
        assertEquals(Phase.BITE, FishingStates.fromState("mordida"));
        assertEquals(Phase.FIGHT, FishingStates.fromState(" Briga "));
        assertEquals(Phase.REEL, FishingStates.fromState("recolher"));
        assertEquals(Phase.CATCH, FishingStates.fromState("captura"));
        assertEquals(Phase.ESCAPE, FishingStates.fromState("escapou"));
        assertEquals(Phase.READY, FishingStates.fromState("parada"));
        assertEquals(Phase.CAST, FishingStates.fromState("lancar"));
        assertEquals(Phase.DRAW, FishingStates.fromState("sacar"));
        assertEquals(Phase.FIGHT, FishingStates.fromState("fishing:fight"));
        assertEquals(Phase.CATCH, FishingStates.fromState("catch"));
        assertEquals(Phase.CATCH, FishingStates.fromState("captura_rara"));
        assertEquals(Phase.FIGHT, FishingStates.fromState("briga_erro"));
        assertEquals(Phase.ESCAPE, FishingStates.fromState("fuga"));
        assertNull(FishingStates.fromState("aura"));
        assertNull(FishingStates.fromState(""));
        assertNull(FishingStates.fromState(null));
        assertEquals(0.42f, FishingStates.parseTension("0.42"), 1e-6f);
        assertEquals(1f, FishingStates.parseTension("3"), 1e-6f);
        assertEquals(0f, FishingStates.parseTension("-1"), 1e-6f);
        assertEquals(0f, FishingStates.parseTension("NaN"), 1e-6f);
        assertEquals(0f, FishingStates.parseTension("x"), 1e-6f);
        assertEquals(0f, FishingStates.parseTension(null), 1e-6f);
    }

    @Test
    void theDefaultModelHasEveryPhase() {
        AnimationLibrary library = defaultLibrary();
        for (Phase p : Phase.values()) {
            if (p.animation != null) {
                AnimationClip clip = library.get(p.animation);
                assertNotNull(clip, p.animation);
                assertTrue(clip.length > 0, p.animation + " has a length");
            }
        }
        // the cast throws early: the bobber leaves with the click, not 0.37 s later as swing:fishing_rod did
        assertTrue(library.get("fishing:cast").length <= 20, "cast within a second");
    }

    @Test
    void aWholeCatch() {
        AnimationLibrary library = defaultLibrary();
        FishingStates.Tracker t = new FishingStates.Tracker();
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        s.external = null;
        s.mainhand = SWORD;
        float now = 0;
        assertEquals(Phase.NONE, t.update(s, now, library));
        // the rod comes to the hand: drawn, then ready
        s.mainhand = ROD;
        assertEquals(Phase.DRAW, t.update(s, ++now, library));
        now += library.get("fishing:draw").length + 4;
        assertEquals(Phase.READY, t.update(s, now, library));
        // the click: cast at once, before the bobber exists
        swing(s);
        assertEquals(Phase.CAST, t.update(s, ++now, library));
        int castSerial = t.serial();
        s.swingTime = 3;
        // the bobber appears a round trip later: the same cast goes on
        now += 3;
        s.fishing = true;
        assertEquals(Phase.CAST, t.update(s, now, library));
        assertEquals(castSerial, t.serial(), "not restarted");
        s.swinging = false;
        now += library.get("fishing:cast").length + 2;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        // the server: a bite, then the fight with tension
        states.set(PLAYER, FishingStates.STATE, "mordida");
        s.external = states.of(PLAYER);
        assertEquals(Phase.BITE, t.update(s, ++now, library));
        states.set(PLAYER, FishingStates.STATE, "briga");
        states.set(PLAYER, FishingStates.TENSION, "0.8");
        assertEquals(Phase.FIGHT, t.update(s, ++now, library));
        for (int i = 0; i < 40; i++) {
            t.update(s, ++now, library);
        }
        assertEquals(Phase.FIGHT, t.phase(), "the fight loops");
        assertEquals(0.8f, t.tension(), 1e-3f);
        // the minigame key (sneak) heaves; a right click does nothing during the fight
        s.sneaking = true;
        swing(s);
        t.update(s, ++now, library);
        assertEquals(Phase.FIGHT, t.phase(), "a swing in the fight is not a retrieve");
        for (int i = 0; i < 6; i++) {
            t.update(s, ++now, library);
        }
        assertTrue(t.heave() > 0.9f, "heaving: " + t.heave());
        s.sneaking = false;
        s.swinging = false;
        for (int i = 0; i < 10; i++) {
            t.update(s, ++now, library);
        }
        assertTrue(t.heave() < 0.01f, "let go: " + t.heave());
        // won: the catch plays through the line coming back
        states.set(PLAYER, FishingStates.STATE, "captura");
        assertEquals(Phase.CATCH, t.update(s, ++now, library));
        s.fishing = false;
        assertEquals(Phase.CATCH, t.update(s, ++now, library), "the line coming back is part of the catch");
        now += library.get("fishing:catch").length + 2;
        assertEquals(Phase.READY, t.update(s, now, library));
        // switching away: nothing; back: drawn again
        s.mainhand = SWORD;
        assertEquals(Phase.NONE, t.update(s, ++now, library));
        s.mainhand = ROD;
        assertEquals(Phase.DRAW, t.update(s, ++now, library));
    }

    @Test
    void escapesRetrievesAndRecasts() {
        AnimationLibrary library = defaultLibrary();
        FishingStates.Tracker t = new FishingStates.Tracker();
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        s.external = states.of(PLAYER);
        // first seen already fishing: straight to waiting, no draw nor cast
        s.mainhand = ROD;
        s.fishing = true;
        float now = 0;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        // a bite and a fight that escapes
        states.set(PLAYER, FishingStates.STATE, "mordida");
        s.external = states.of(PLAYER);
        assertEquals(Phase.BITE, t.update(s, ++now, library));
        // the bite clip ends before the player presses the key: back to waiting
        now += library.get("fishing:bite").length + 2;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        states.set(PLAYER, FishingStates.STATE, "briga");
        assertEquals(Phase.FIGHT, t.update(s, ++now, library));
        states.set(PLAYER, FishingStates.STATE, "escapou");
        assertEquals(Phase.ESCAPE, t.update(s, ++now, library));
        now += library.get("fishing:escape").length + 2;
        assertEquals(Phase.WAIT, t.update(s, now, library), "the line is still out");
        // back to parada in the middle of the fight with the line out: the fish got away (AkashicFishing tells a rod
        // without a fuga state parada)
        states.set(PLAYER, FishingStates.STATE, "mordida");
        t.update(s, ++now, library);
        states.set(PLAYER, FishingStates.STATE, "parada");
        assertEquals(Phase.ESCAPE, t.update(s, ++now, library));
        now += library.get("fishing:escape").length + 2;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        // a miss reaction (briga_erro) and back: the fight loop is not restarted
        states.set(PLAYER, FishingStates.STATE, "briga");
        assertEquals(Phase.FIGHT, t.update(s, ++now, library));
        int fight = t.serial();
        states.set(PLAYER, FishingStates.STATE, "briga_erro");
        assertEquals(Phase.FIGHT, t.update(s, ++now, library));
        states.set(PLAYER, FishingStates.STATE, "briga");
        assertEquals(Phase.FIGHT, t.update(s, ++now, library));
        assertEquals(fight, t.serial(), "the same fight");
        states.set(PLAYER, FishingStates.STATE, "captura_mitica");
        assertEquals(Phase.CATCH, t.update(s, ++now, library));
        now += library.get("fishing:catch").length + 2;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        // a right click with the line out: the retrieve starts with the click; the line coming back is the same
        swing(s);
        assertEquals(Phase.RETRIEVE, t.update(s, ++now, library));
        int serial = t.serial();
        s.swingTime = 2;
        now += 2;
        s.fishing = false;
        assertEquals(Phase.RETRIEVE, t.update(s, now, library));
        assertEquals(serial, t.serial(), "not restarted by the line coming back");
        s.swinging = false;
        now += library.get("fishing:retrieve").length + 2;
        assertEquals(Phase.READY, t.update(s, now, library));
        // the bobber appearing without a click (a plugin recasting the rod): a cast of its own
        s.fishing = true;
        assertEquals(Phase.CAST, t.update(s, ++now, library));
        now += library.get("fishing:cast").length + 2;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        // the line vanishing on its own while waiting (pulled away): the rod flicks back
        s.fishing = false;
        assertEquals(Phase.RETRIEVE, t.update(s, ++now, library));
        // the same state set again restarts it (a second bite)
        s.fishing = true;
        now += 60;
        t.update(s, now, library);
        states.set(PLAYER, FishingStates.STATE, "mordida");
        assertEquals(Phase.BITE, t.update(s, ++now, library));
        int first = t.serial();
        states.set(PLAYER, FishingStates.STATE, null);
        states.set(PLAYER, FishingStates.STATE, "mordida");
        assertEquals(Phase.BITE, t.update(s, ++now, library));
        assertNotEquals(first, t.serial());
        // sleeping puts it away
        s.sleeping = true;
        assertEquals(Phase.NONE, t.update(s, ++now, library));
    }

    @Test
    void aSwingWithoutALineThatNeverCastsEnds() {
        AnimationLibrary library = defaultLibrary();
        FishingStates.Tracker t = new FishingStates.Tracker();
        PlayerSnapshot s = new PlayerSnapshot();
        s.mainhand = ROD;
        float now = 0;
        t.update(s, now, library);
        swing(s);
        assertEquals(Phase.CAST, t.update(s, ++now, library));
        s.swinging = false;
        now += library.get("fishing:cast").length + 2;
        assertEquals(Phase.READY, t.update(s, now, library), "hit something instead: no line, back to ready");
    }

    @Test
    void aSwingThatComesWithTheBobberIsTheCast() {
        // another player: his arm swing and his bobber arrive in the same tick
        AnimationLibrary library = defaultLibrary();
        FishingStates.Tracker t = new FishingStates.Tracker();
        PlayerSnapshot s = new PlayerSnapshot();
        s.mainhand = ROD;
        float now = 0;
        t.update(s, now, library);
        swing(s);
        s.fishing = true;
        assertEquals(Phase.CAST, t.update(s, ++now, library));
    }

    @Test
    void aRecreatedStoreIsStillHeard() {
        // the states were forgotten (a new world) and set again: the generation starts over, the value changed
        AnimationLibrary library = defaultLibrary();
        FishingStates.Tracker t = new FishingStates.Tracker();
        PlayerSnapshot s = new PlayerSnapshot();
        s.mainhand = ROD;
        s.fishing = true;
        float now = 0;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        ExternalStates first = new ExternalStates();
        first.set(PLAYER, FishingStates.STATE, "mordida");
        s.external = first.of(PLAYER);
        assertEquals(Phase.BITE, t.update(s, ++now, library));
        ExternalStates second = new ExternalStates();
        second.set(PLAYER, FishingStates.STATE, "briga");
        s.external = second.of(PLAYER);
        assertEquals(1, s.external.generation(FishingStates.STATE), "the same generation as before");
        assertEquals(Phase.FIGHT, t.update(s, ++now, library));
    }

    @Test
    void easing() {
        assertEquals(0f, FishingStates.Tracker.approach(0, 1, 0.5f, 0), 1e-6f);
        assertEquals(0.5f, FishingStates.Tracker.approach(0, 1, 0.5f, 1), 1e-6f);
        assertEquals(0.75f, FishingStates.Tracker.approach(0, 1, 0.5f, 2), 1e-6f);
        // the same in two half-ticks as in one tick: frame-rate independent
        float half = FishingStates.Tracker.approach(0, 1, 0.5f, 0.5f);
        assertEquals(0.5f, FishingStates.Tracker.approach(half, 1, 0.5f, 0.5f), 1e-5f);
    }

    @Test
    void theModelPlaysTheRod() throws IOException {
        PlayerAnimator a = animator("default");
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        s.mainhand = ROD;
        run(a, s, 0, 5);
        assertNull(a.playing(PlayerControllers.FISHING), "seen with the rod: nothing to draw");
        // the cast replaces swing:fishing_rod
        swing(s);
        run(a, s, 6, 8);
        assertEquals("fishing:cast", a.playing(PlayerControllers.FISHING));
        assertFalse("swing:fishing_rod".equals(a.playing("player.swing")), "the generic swing stays out");
        s.fishing = true;
        run(a, s, 9, 40);
        assertEquals("fishing:wait", a.playing(PlayerControllers.FISHING));
        assertEquals(Phase.WAIT, a.fishingPhase());
        states.set(PLAYER, FishingStates.STATE, "briga");
        states.set(PLAYER, FishingStates.TENSION, "0.5");
        s.external = states.of(PLAYER);
        run(a, s, 41, 80);
        assertEquals("fishing:fight", a.playing(PlayerControllers.FISHING));
        assertEquals(
            "fishing:fight",
            a.context()
                .call("ctrl", "fishing_animation", new MolangValue[0])
                .asString());
        assertEquals(
            0.5,
            a.context()
                .call("ctrl", "fishing_tension", new MolangValue[0])
                .asNumber(),
            1e-3);
        states.set(PLAYER, FishingStates.STATE, "captura");
        run(a, s, 81, 83);
        assertEquals("fishing:catch", a.playing(PlayerControllers.FISHING));
        s.fishing = false;
        run(a, s, 84, 140);
        String after = a.playing(PlayerControllers.FISHING);
        assertTrue(after == null || after.equals("fishing:catch"), "the catch ends, nothing after: " + after);
        assertEquals(Phase.READY, a.fishingPhase());
        // a sword: the controller stays out, the sword's swing plays
        s.mainhand = SWORD;
        run(a, s, 141, 150);
        swing(s);
        run(a, s, 151, 152);
        assertEquals("swing:sword", a.playing("player.swing"));
        assertEquals(Phase.NONE, a.fishingPhase());
    }

    /** UpBody's x rotation (radians) after a fight of 60 ticks at this tension, sneaking (the minigame key) or not. */
    private static float fightLean(String tension, boolean sneaking) throws IOException {
        PlayerAnimator a = animator("default");
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        s.mainhand = ROD;
        s.fishing = true;
        states.set(PLAYER, FishingStates.STATE, "briga");
        states.set(PLAYER, FishingStates.TENSION, tension);
        s.external = states.of(PLAYER);
        s.sneaking = sneaking;
        run(a, s, 0, 60);
        assertEquals("fishing:fight", a.playing(PlayerControllers.FISHING));
        int bone = -1;
        for (int i = 0; i < a.model().geometry.bones.length; i++) {
            if ("UpBody".equals(a.model().geometry.bones[i].name)) {
                bone = i;
            }
        }
        return a.pose().values[bone * BonePose.FLOATS];
    }

    @Test
    void tensionAndTheKeyBendTheFight() throws IOException {
        float calm = fightLean("0", false);
        float tense = fightLean("1", false);
        float heaving = fightLean("0", true);
        // tension leans back 10 degrees more, a heave 9 more (same frames, so the same point of the loop)
        assertTrue(Math.abs(tense - calm) > 0.12f, "tension leans: " + calm + " -> " + tense);
        assertTrue(Math.abs(heaving - calm) > 0.1f, "the key heaves: " + calm + " -> " + heaving);
        assertTrue(Math.signum(tense - calm) == Math.signum(heaving - calm), "both lean the same way");
    }

    /** The rod's axis in model space after a few ticks of a phase (x right, y up, z back). */
    private static float[] rodAfter(String model, String state, boolean line) throws IOException {
        PlayerAnimator a = animator(model);
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        s.mainhand = ROD;
        s.fishing = line;
        if (state != null) {
            states.set(PLAYER, FishingStates.STATE, state);
            states.set(PLAYER, FishingStates.TENSION, "0.5");
            s.external = states.of(PLAYER);
        }
        run(a, s, 0, 40);
        return new RodAim(a.model().geometry).rodInModel(a.pose());
    }

    @Test
    void theRodStaysStraightOnEveryModel() throws IOException {
        // both hands meet in front of the body: each arm turns inward, and every model has its own arms; the rod still
        // points straight ahead of the torso (only its rise is the clip's)
        for (String model : new String[] { "default", "misc/1_alex", "wine_fox/13_matured", "wine_fox/22_elf" }) {
            for (String state : new String[] { null, "briga", "recolher", "ready" }) {
                float[] rod = "ready".equals(state) ? rodAfter(model, null, false) : rodAfter(model, state, true);
                String what = model + " " + (state == null ? "wait" : state);
                assertTrue(Math.abs(rod[0]) < 0.03f, what + ": no slant, x=" + rod[0]);
                if (!"ready".equals(state)) {
                    assertTrue(rod[1] > 0.3f, what + ": the rod rises, y=" + rod[1]);
                }
            }
        }
    }

    @Test
    void withTheLineOutTheRodPointsWhereThePlayerLooks() throws IOException {
        // 1.7.10's body lags the head by up to 75 degrees when standing: the player (and the camera) look at the
        // bobber, the body faces elsewhere; the rod follows the look, the torso turns half of it
        for (float look : new float[] { 30, -45 }) {
            PlayerAnimator a = animator("default");
            PlayerSnapshot s = new PlayerSnapshot();
            s.mainhand = ROD;
            s.fishing = true;
            s.bodyYaw = 0;
            s.headYaw = look;
            run(a, s, 0, 40);
            float[] rod = new RodAim(a.model().geometry).rodInModel(a.pose());
            // the pose's look (netHeadYaw is the negated head turn) applied to straight ahead (0, 0, -1)
            double want = Math.toRadians(-look);
            double rodTurn = Math.atan2(-rod[0], -rod[2]);
            assertEquals(Math.toDegrees(-Math.sin(want) == 0 ? 0 : want), Math.toDegrees(rodTurn), 2.0, "look " + look);
            int torso = a.model().geometry.indexOf("UpBody");
            assertTrue(
                Math.abs(a.pose().values[torso * BonePose.FLOATS + 1]) > Math.toRadians(Math.abs(look) * 0.4),
                "the torso turns toward the look");
        }
        // only holding the rod (no line): straight ahead of the body, whatever the look
        PlayerAnimator a = animator("default");
        PlayerSnapshot s = new PlayerSnapshot();
        s.mainhand = ROD;
        s.headYaw = 40;
        run(a, s, 0, 40);
        float[] rod = new RodAim(a.model().geometry).rodInModel(a.pose());
        assertTrue(Math.abs(rod[0]) < 0.03f, "straight ahead of the body: x=" + rod[0]);
    }

    @Test
    void theReactionsOfTheRod() {
        assertEquals(Phase.PET, FishingStates.fromState("carinho"));
        assertEquals(Phase.INSPECT, FishingStates.fromState("inspecionar"));
        assertEquals(Phase.LAZY, FishingStates.fromState("ociosa"));
        assertEquals(Phase.GREET, FishingStates.fromState("encontro"));
        assertEquals(Phase.RIVAL, FishingStates.fromState("rivalidade"));
        assertEquals(Phase.CELEBRATE, FishingStates.fromState("comemoracao"));
        AnimationLibrary library = defaultLibrary();
        for (Phase p : new Phase[] { Phase.PET, Phase.INSPECT, Phase.LAZY, Phase.GREET, Phase.RIVAL,
            Phase.CELEBRATE }) {
            assertNotNull(library.get(p.animation), p.animation);
        }
        FishingStates.Tracker t = new FishingStates.Tracker();
        PlayerSnapshot s = new PlayerSnapshot();
        ExternalStates states = new ExternalStates();
        s.mainhand = ROD;
        float now = 0;
        t.update(s, now, library);
        // Shift and a left click: no cast, the server's carinho
        s.sneaking = true;
        swing(s);
        assertEquals(Phase.READY, t.update(s, ++now, library), "a gesture is not a cast");
        states.set(PLAYER, FishingStates.STATE, "carinho");
        s.external = states.of(PLAYER);
        assertEquals(Phase.PET, t.update(s, ++now, library));
        s.swinging = false;
        s.sneaking = false;
        now += library.get("fishing:pet").length + 4;
        assertEquals(Phase.READY, t.update(s, now, library), "a reaction plays once");
        // idle 30 s: loops with the line in, until the player moves (parada)
        states.set(PLAYER, FishingStates.STATE, "ociosa");
        assertEquals(Phase.LAZY, t.update(s, ++now, library));
        now += 200;
        assertEquals(Phase.LAZY, t.update(s, now, library), "loops with the line in");
        states.set(PLAYER, FishingStates.STATE, "parada");
        assertEquals(Phase.READY, t.update(s, ++now, library), "moved: over");
        // a reaction that starts as the line comes back is not cut short by the line
        s.fishing = true;
        now += 60;
        t.update(s, now, library);
        now += 60;
        assertEquals(Phase.WAIT, t.update(s, now, library));
        s.fishing = false;
        states.set(PLAYER, FishingStates.STATE, "encontro");
        assertEquals(Phase.GREET, t.update(s, ++now, library));
    }

    @Test
    void everyModelFallsBackToTheDefaultSet() throws IOException {
        for (String model : new String[] { "misc/1_alex", "wine_fox/13_matured" }) {
            PlayerAnimator a = animator(model);
            PlayerSnapshot s = new PlayerSnapshot();
            s.mainhand = ROD;
            s.fishing = true;
            run(a, s, 0, 20);
            assertNotNull(a.model().library.get("fishing:fight"), model);
            assertEquals("fishing:wait", a.playing(PlayerControllers.FISHING), model);
        }
    }
}

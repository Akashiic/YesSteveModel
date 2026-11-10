package com.akashic.futura.anim;

import static com.akashic.futura.anim.Tier2Support.animator;
import static com.akashic.futura.anim.Tier2Support.functions;
import static com.akashic.futura.anim.Tier2Support.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.akashic.futura.molang.MolangValue;

class SoundEventsTest {

    @Test
    void soundKeyframesPlayWhenPassedAndStopWhenTheLoopRestarts() {
        String clips = "\"idle\":{\"loop\":true,\"animation_length\":1,"
            + "\"sound_effects\":{\"0.25\":{\"effect\":\"step\"},\"0.5\":[{\"effect\":\"\"},{\"effect\":\"clap\"}]},"
            + "\"bones\":{\"Root\":{\"position\":[0,1,0]}}}";
        PlayerAnimator a = animator(clips, null, null);
        Tier2Support.Sounds sounds = new Tier2Support.Sounds();
        a.setSoundBackend(sounds);
        PlayerSnapshot s = new PlayerSnapshot();
        // 2 ticks of blending in, then 5 ticks = 0.25 s
        run(a, s, 0, 6);
        assertTrue(sounds.played.isEmpty());
        run(a, s, 7, 7);
        assertEquals(Arrays.asList("step"), sounds.played);
        run(a, s, 8, 12);
        // an empty effect is skipped
        assertEquals(Arrays.asList("step", "clap"), sounds.played);
        assertEquals(2, sounds.playing());
        run(a, s, 13, 23);
        // the loop restarted: the timeline's sounds stopped
        assertEquals(0, sounds.playing());
        run(a, s, 24, 27);
        assertEquals(Arrays.asList("step", "clap", "step"), sounds.played);
    }

    @Test
    void playSoundModesAndStop() {
        PlayerAnimator a = animator(
            Tier2Support.up("idle", "Root", 1, true, 1),
            null,
            functions(
                "player@player_update",
                "v.step == 1 ? { ysm.play_sound('a', 'horn'); ysm.play_sound('a', 'horn'); ysm.play_sound(0, 'tick');"
                    + " ysm.play_sound(0, 'tick'); ysm.play_sound('m', 'motor', 7, 0.5, 2); };"
                    + " v.step == 2 ? { ysm.play_sound('a', 'horn_long', 1); ysm.stop_sound('m', true); };"
                    + " v.step == 3 ? { ysm.stop_all_sounds(); ysm.play_sound(-1, 'never'); ysm.play_sound('x', '');"
                    + " ysm.play_sound('vanilla', 'minecraft:random.click'); };"
                    + " v.step = 0;"));
        Tier2Support.Sounds sounds = new Tier2Support.Sounds();
        a.setSoundBackend(sounds);
        PlayerSnapshot s = new PlayerSnapshot();
        a.variables()
            .set("step", MolangValue.ONE);
        run(a, s, 0, 0);
        // the same id twice is refused; anonymous ids always play; mode 7 = force, global, loop
        assertEquals(Arrays.asList("horn", "tick", "tick", "motor loop v0.5 p2.0"), sounds.played);
        a.variables()
            .set("step", MolangValue.of(2.0));
        run(a, s, 1, 1);
        // mode 1 forces the id over the playing one; stop_sound(id, global) stops the global one
        assertFalse(sounds.isPlaying("horn"));
        assertTrue(sounds.isPlaying("horn_long"));
        assertFalse(sounds.isPlaying("motor"));
        a.variables()
            .set("step", MolangValue.of(3.0));
        run(a, s, 2, 2);
        assertEquals(
            0,
            sounds.handles.stream()
                .filter(h -> !h.stopped && !h.sound.contains(":"))
                .count());
        assertTrue(sounds.isPlaying("minecraft:random.click"));
        assertFalse(sounds.played.contains("never"));
    }

    @Test
    void stringAndNumericIdsDoNotAlias() {
        SoundScope scope = new SoundScope();
        Tier2Support.Sounds sounds = new Tier2Support.Sounds();
        assertTrue(scope.play(sounds, 5, "a", false, 1, 1, false));
        assertTrue(scope.play(sounds, "5", "b", false, 1, 1, false));
        assertFalse(scope.play(sounds, 5, "c", false, 1, 1, false));
        assertTrue(scope.stop("5"));
        assertTrue(sounds.isPlaying("a"));
        assertFalse(scope.stop(0));
        scope.stopAll();
        assertEquals(0, sounds.playing());
        assertEquals(Integer.valueOf(3), SoundScope.id(MolangValue.of(3.7)));
        assertEquals("0", SoundScope.id(MolangValue.of("0")));
    }
}

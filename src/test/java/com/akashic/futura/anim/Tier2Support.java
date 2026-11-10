package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.format.pojo.controller.AnimationControllerFile;
import com.akashic.futura.geo.BakedGeometry;

/** Helpers for the Tier 2 tests: real Molang everywhere, Bedrock controllers and functions from JSON and text. */
final class Tier2Support {

    static final BakedGeometry GEO = AnimTestSupport.geometry("Root", "Body:Root", "Head:Body", "Arm:Body");

    private Tier2Support() {}

    /** Clips compiled with the real Molang interpreter. */
    static AnimationLibrary library(String animationsJson) {
        AnimationFile file = FormatGson.create()
            .fromJson("{\"format_version\":\"1.8.0\",\"animations\":{" + animationsJson + "}}", AnimationFile.class);
        Map<String, AnimationFile> sets = new LinkedHashMap<>();
        sets.put("main", file);
        return new AnimationLibrary(AnimationLibrary.compile(sets, new ClipCompiler()), null);
    }

    /** Controllers from the content of a file's {@code "animation_controllers"} object. */
    static Map<String, ControllerData> controllers(String json) {
        AnimationControllerFile file = FormatGson.create()
            .fromJson(
                "{\"format_version\":\"1.19.0\",\"animation_controllers\":{" + json + "}}",
                AnimationControllerFile.class);
        return ControllerData.compile(file.animationControllers);
    }

    /** Functions from alternating file names and sources. */
    static ModelFunctions functions(String... nameAndSource) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameAndSource.length; i += 2) {
            sources.put(nameAndSource[i], nameAndSource[i + 1]);
        }
        return ModelFunctions.compile(sources);
    }

    static PlayerAnimator animator(String clips, String controllers, ModelFunctions functions) {
        return new PlayerAnimator(
            ModelAnimations
                .of(GEO, library(clips), "", controllers == null ? null : controllers(controllers), functions));
    }

    /** A clip moving a bone up by a number of pixels. */
    static String up(String name, String bone, float pixels, boolean loop, float seconds) {
        return "\"" + name
            + "\":{"
            + (loop ? "\"loop\":true," : "")
            + "\"animation_length\":"
            + seconds
            + ",\"bones\":{\""
            + bone
            + "\":{\"position\":[0,"
            + pixels
            + ",0]}}}";
    }

    /** Runs whole ticks from {@code from} to {@code to}, inclusive. */
    static void run(PlayerAnimator a, PlayerSnapshot s, int from, int to) {
        for (int t = from; t <= to; t++) {
            s.ticksExisted = t;
            a.update(s, t);
        }
    }

    /** A bone's animated height in pixels. */
    static float up(PlayerAnimator a, String bone) {
        return a.pose().values[GEO.indexOf(bone) * BonePose.FLOATS + 4] * 16;
    }

    static double var(PlayerAnimator a, String name) {
        return a.variables()
            .get(name)
            .asNumber();
    }

    /** A sound backend that records what it was asked to play. */
    static final class Sounds implements SoundBackend {

        final List<String> played = new ArrayList<>();
        final List<Handle> handles = new ArrayList<>();

        @Override
        public SoundHandle play(String sound, float volume, float pitch, boolean loop) {
            played.add(
                sound + (loop ? " loop" : "") + (volume != 1 ? " v" + volume : "") + (pitch != 1 ? " p" + pitch : ""));
            Handle h = new Handle(sound);
            handles.add(h);
            return h;
        }

        long playing() {
            return handles.stream()
                .filter(h -> !h.stopped)
                .count();
        }

        boolean isPlaying(String sound) {
            return handles.stream()
                .anyMatch(h -> !h.stopped && h.sound.equals(sound));
        }
    }

    static final class Handle implements SoundBackend.SoundHandle {

        final String sound;
        boolean stopped;

        Handle(String sound) {
            this.sound = sound;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}

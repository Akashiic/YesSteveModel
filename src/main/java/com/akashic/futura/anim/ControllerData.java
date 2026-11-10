/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/AnimationControllerBuilder.java,
 * src/main/java/com/elfmcys/ysm/model/resource/client/render/AnimationProtoMapper.java (controllerFile, state, blend),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/builder/controller/AnimationControllerData.java and
 * AnimationControllerState.java, Apache License 2.0.
 * Changes: compiled straight from the format POJOs (no protobuf container); controller-state sound effects are kept
 * (upstream 2.x drops them when mapping the container).
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import com.akashic.futura.format.pojo.controller.AnimationController;
import com.akashic.futura.format.pojo.controller.AnimationEntry;
import com.akashic.futura.format.pojo.controller.SoundEffect;
import com.akashic.futura.format.pojo.controller.State;
import com.akashic.futura.format.pojo.controller.Transition;
import com.akashic.futura.molang.Molang;
import com.akashic.futura.molang.MolangExpression;

/** One Bedrock animation controller of a model, compiled: its states by name and the initial one. Immutable. */
public final class ControllerData {

    /** A state that hands control back to the coded controller of the same name ({@link HybridController}). */
    public static final String BUILTIN_STATE = "ysm-builtin";
    /** {@code ysm-entry-<x>} runs the sub-controller {@code <controller>.<x>} while in the state. */
    public static final String SUB_ENTRY_PREFIX = "ysm-entry-";

    /** One state. */
    public static final class StateData {

        public final String name;
        /** {@link #BUILTIN_STATE}. */
        public final boolean builtin;
        /** The {@code <x>} of {@code ysm-entry-<x>}, or null. */
        public final String subEntry;
        /** Animation names, in declaration order. */
        final String[] animations;
        /** Their conditions; a null entry always applies. */
        final MolangExpression[] animationConditions;
        /** Transition targets and conditions, in declaration order (the first true one wins). */
        final String[] targets;
        final MolangExpression[] conditions;
        /** Sounds played when the state is entered. */
        final String[] soundEffects;
        /** {@code on_entry} / {@code on_exit} statements joined into one program, or null. */
        final MolangExpression onEntry;
        final MolangExpression onExit;
        public final BlendCurve blend;
        /** Read and kept; blending is already by normalised quaternion (shortest path), as upstream. */
        public final boolean blendViaShortestPath;

        StateData(String name, String[] animations, MolangExpression[] animationConditions, String[] targets,
            MolangExpression[] conditions, String[] soundEffects, MolangExpression onEntry, MolangExpression onExit,
            BlendCurve blend, boolean blendViaShortestPath) {
            this.name = name;
            this.builtin = BUILTIN_STATE.equals(name);
            this.subEntry = name.startsWith(SUB_ENTRY_PREFIX) ? name.substring(SUB_ENTRY_PREFIX.length()) : null;
            this.animations = animations;
            this.animationConditions = animationConditions;
            this.targets = targets;
            this.conditions = conditions;
            this.soundEffects = soundEffects;
            this.onEntry = onEntry;
            this.onExit = onExit;
            this.blend = blend;
            this.blendViaShortestPath = blendViaShortestPath;
        }

        public int animationCount() {
            return animations.length;
        }
    }

    public final String name;
    public final String initialState;
    public final Map<String, StateData> states;

    private ControllerData(String name, String initialState, Map<String, StateData> states) {
        this.name = name;
        this.initialState = initialState;
        this.states = Collections.unmodifiableMap(states);
    }

    /** Compiles every controller of a model with {@link Molang#parse}. */
    public static Map<String, ControllerData> compile(Map<String, AnimationController> controllers) {
        return compile(controllers, Molang::parse);
    }

    /**
     * @param parser compiles Molang (tests stub it)
     * @return the controllers by name, sorted
     */
    public static Map<String, ControllerData> compile(Map<String, AnimationController> controllers,
        Function<String, MolangExpression> parser) {
        Map<String, ControllerData> result = new TreeMap<>();
        if (controllers == null) {
            return result;
        }
        for (Map.Entry<String, AnimationController> e : controllers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                result.put(e.getKey(), compile(e.getKey(), e.getValue(), parser));
            }
        }
        return result;
    }

    static ControllerData compile(String name, AnimationController src, Function<String, MolangExpression> parser) {
        Map<String, StateData> states = new LinkedHashMap<>();
        if (src.states != null) {
            for (Map.Entry<String, State> e : new TreeMap<>(src.states).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    states.put(e.getKey(), state(e.getKey(), e.getValue(), parser));
                }
            }
        }
        String initial = src.initialState == null ? "default" : src.initialState;
        return new ControllerData(name, initial, states);
    }

    private static StateData state(String name, State src, Function<String, MolangExpression> parser) {
        List<String> animations = new ArrayList<>();
        List<MolangExpression> animationConditions = new ArrayList<>();
        if (src.animations != null) {
            for (AnimationEntry entry : src.animations) {
                if (entry == null) {
                    continue;
                }
                animations.add(entry.name == null ? "" : entry.name);
                String condition = entry.condition == null ? "" : entry.condition;
                animationConditions.add(
                    condition.trim()
                        .isEmpty() ? null : parser.apply(condition));
            }
        }
        List<String> targets = new ArrayList<>();
        List<MolangExpression> conditions = new ArrayList<>();
        if (src.transitions != null) {
            for (Transition transition : src.transitions) {
                if (transition == null) {
                    continue;
                }
                targets.add(transition.destStateName == null ? "" : transition.destStateName);
                conditions.add(parser.apply(transition.condition == null ? "" : transition.condition));
            }
        }
        List<String> sounds = new ArrayList<>();
        if (src.soundEffects != null) {
            for (SoundEffect sound : src.soundEffects) {
                if (sound != null && sound.effect != null
                    && !sound.effect.trim()
                        .isEmpty()) {
                    sounds.add(sound.effect);
                }
            }
        }
        return new StateData(
            name,
            animations.toArray(new String[0]),
            animationConditions.toArray(new MolangExpression[0]),
            targets.toArray(new String[0]),
            conditions.toArray(new MolangExpression[0]),
            sounds.toArray(new String[0]),
            statements(src.onEntry, parser),
            statements(src.onExit, parser),
            BlendCurve.of(src.blendTransition),
            src.blendViaShortestPath);
    }

    /** Upstream {@code FrontendPrograms.compileStatements}: one program, each statement ended by {@code ;}. */
    static MolangExpression statements(List<String> sources, Function<String, MolangExpression> parser) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        StringBuilder combined = new StringBuilder();
        for (String source : sources) {
            String statement = source == null ? "" : stripTrailing(source);
            combined.append(statement);
            if (!statement.endsWith(";")) {
                combined.append(';');
            }
            combined.append('\n');
        }
        return parser.apply(combined.toString());
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/model/resource/client/AnimationStore.java,
 * src/main/java/com/elfmcys/ysm/format/schema/baked/asset/BakedAnimationSet.java (name index) and
 * src/main/java/com/elfmcys/ysm/expression/inventory/ExpressionSet.java (which sets are third person), Apache License
 * 2.0.
 * Changes: compiled eagerly from the POJOs, no lazy loading, failure gates or read phases.
 */
package com.akashic.futura.anim;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.akashic.futura.format.pojo.animation.Animation;
import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.model.LoadedModel;

/**
 * The animations of a model by name, with the default model's animations behind them: a name the model lacks resolves
 * to the default animation of the same name. A model has two: the third-person body ({@link #of}, every set but
 * {@code fp_arm}) and the first-person arms ({@link #ofFirstPerson}, only {@code fp_arm}).
 */
public final class AnimationLibrary {

    /** The set upstream keeps for the first-person arms; every other set belongs to the third-person body. */
    public static final String FIRST_PERSON_SET = "fp_arm";

    /** Result of a lookup. */
    public static final class Resolution {

        public final AnimationClip clip;
        /** true when the clip came from the fallback library */
        public final boolean fallback;

        Resolution(AnimationClip clip, boolean fallback) {
            this.clip = clip;
            this.fallback = fallback;
        }
    }

    private final Map<String, Resolution> local = new LinkedHashMap<>();
    /** local plus the fallback's entries marked as fallback */
    private final Map<String, Resolution> all = new LinkedHashMap<>();
    private final AnimationLibrary fallback;
    private final Set<String> names;

    /**
     * @param clips    the model's own clips by name
     * @param fallback the default model's library, or null
     */
    public AnimationLibrary(Map<String, AnimationClip> clips, AnimationLibrary fallback) {
        for (Map.Entry<String, AnimationClip> e : clips.entrySet()) {
            local.put(e.getKey(), new Resolution(e.getValue(), false));
        }
        this.fallback = fallback;
        all.putAll(local);
        if (fallback != null) {
            for (String name : fallback.names()) {
                if (!all.containsKey(name)) {
                    all.put(name, new Resolution(fallback.resolve(name).clip, true));
                }
            }
        }
        this.names = Collections.unmodifiableSet(new LinkedHashSet<>(all.keySet()));
    }

    /**
     * Compiles every third-person set of a model (all sets but {@code fp_arm}, in manifest order; a name repeated in a
     * later set replaces the earlier one, as upstream's name index does).
     */
    public static AnimationLibrary of(LoadedModel model, AnimationLibrary fallback, ClipCompiler compiler) {
        return new AnimationLibrary(compile(model.animations, compiler), fallback);
    }

    /**
     * Compiles the first-person set ({@code fp_arm}) of a model; its fallback is the default model's {@code fp_arm}
     * (upstream's {@code player/first-person} default domain).
     */
    public static AnimationLibrary ofFirstPerson(LoadedModel model, AnimationLibrary fallback, ClipCompiler compiler) {
        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        AnimationFile set = model.animations.get(FIRST_PERSON_SET);
        if (set != null) {
            for (Animation animation : set.animations) {
                clips.put(animation.animationName, compiler.compile(animation));
            }
        }
        return new AnimationLibrary(clips, fallback);
    }

    static Map<String, AnimationClip> compile(Map<String, AnimationFile> sets, ClipCompiler compiler) {
        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        for (Map.Entry<String, AnimationFile> set : sets.entrySet()) {
            if (FIRST_PERSON_SET.equals(set.getKey()) || set.getValue() == null) {
                continue;
            }
            for (Animation animation : set.getValue().animations) {
                clips.put(animation.animationName, compiler.compile(animation));
            }
        }
        return clips;
    }

    /** @return the clip and where it came from, or null if neither the model nor the fallback has it */
    public Resolution resolve(String name) {
        return all.get(name);
    }

    /** @return the clip (own or fallback), or null */
    public AnimationClip get(String name) {
        Resolution r = resolve(name);
        return r == null ? null : r.clip;
    }

    public boolean contains(String name) {
        return names.contains(name);
    }

    /** Every name, own first then the fallback's. */
    public Set<String> names() {
        return names;
    }

    public boolean containsLocal(String name) {
        return local.containsKey(name);
    }

    public AnimationLibrary fallback() {
        return fallback;
    }
}

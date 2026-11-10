/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/SingleControllerDiscovery.java and
 * src/main/java/com/elfmcys/ysm/client/controller/ParallelControllerDiscovery.java (which controllers a projectile or
 * vehicle target gets), src/main/java/com/elfmcys/ysm/model/resource/client/ProjectileModelResources.java and
 * VehicleModelResources.java, Apache License 2.0.
 * Changes: the per-target part of the discovery (names, Bedrock definitions, functions); the controllers are built per
 * entity in EntityAnimator.
 */
package com.akashic.futura.anim;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ReplacementTarget;

/**
 * Everything about a projectile or vehicle replacement's animations that does not depend on the entity: compiled
 * clips (the default model's target of the same match list behind them), Bedrock controllers, the model's functions,
 * and which optional controllers exist. Build once per target and share between every {@link EntityAnimator}.
 */
public final class EntityAnimations implements AnimationPlayer.BoneIndex {

    public final ReplacementTarget.Kind kind;
    /** {@code projectile} or {@code vehicle}: the controller category. */
    public final String category;
    public final BakedGeometry geometry;
    public final AnimationLibrary library;
    /** The target's Bedrock controllers by name ({@code projectile.main}...). */
    public final Map<String, ControllerData> controllers;
    /** The model's user functions and coded-controller overrides ({@code projectile_ctrl_main}). */
    public final ModelFunctions functions;
    /** {@code vehicle.pre_parallel_N} controller to animation (null: it stops), sorted; empty for projectiles. */
    final Map<String, String> preParallel;
    /** {@code <category>.parallel_N} controller to animation (null: it stops), sorted. */
    final Map<String, String> parallel;
    final float[] initialRotations;

    private EntityAnimations(ReplacementTarget.Kind kind, BakedGeometry geometry, AnimationLibrary library,
        Map<String, ControllerData> controllers, ModelFunctions functions) {
        this.kind = kind;
        this.category = kind.category;
        this.geometry = geometry;
        this.library = library;
        this.controllers = controllers == null ? Collections.<String, ControllerData>emptyMap()
            : Collections.unmodifiableMap(controllers);
        this.functions = functions == null ? ModelFunctions.EMPTY : functions;
        this.preParallel = kind == ReplacementTarget.Kind.VEHICLE ? discoverParallel("pre_parallel")
            : Collections.<String, String>emptyMap();
        this.parallel = discoverParallel("parallel");
        float[] init = new float[geometry.bones.length * 3];
        for (int i = 0; i < geometry.bones.length; i++) {
            System.arraycopy(geometry.bones[i].rotation, 0, init, i * 3, 3);
        }
        this.initialRotations = init;
    }

    /**
     * Compiles a target of a model, the default model's target of the same match list behind it (none for the default
     * model itself, nor when the default model has no such target).
     */
    public static EntityAnimations compile(LoadedModel model, ReplacementTarget target) {
        AnimationLibrary fallback = DefaultAnimations.isDefault(model.source) ? null
            : DefaultAnimations.replacementLibrary(target.domain());
        return compile(model, target, fallback, new ClipCompiler());
    }

    /**
     * @param fallback the library missing names fall back to, or null
     * @param compiler compiles the clips (tests stub Molang through it)
     */
    public static EntityAnimations compile(LoadedModel model, ReplacementTarget target, AnimationLibrary fallback,
        ClipCompiler compiler) {
        return new EntityAnimations(
            target.kind,
            target.geometry,
            library(target, fallback, compiler),
            ControllerData.compile(target.controllers, compiler.parser()),
            ModelFunctions.compile(model.functions, compiler.parser()));
    }

    /** The target's own clips (its {@code animation} file), with a fallback behind them. */
    static AnimationLibrary library(ReplacementTarget target, AnimationLibrary fallback, ClipCompiler compiler) {
        Map<String, AnimationFile> sets = new LinkedHashMap<>();
        if (target.animation != null) {
            sets.put("main", target.animation);
        }
        return new AnimationLibrary(AnimationLibrary.compile(sets, compiler), fallback);
    }

    /** For tests: a geometry, a ready library, controllers and functions. */
    public static EntityAnimations of(ReplacementTarget.Kind kind, BakedGeometry geometry, AnimationLibrary library,
        Map<String, ControllerData> controllers, ModelFunctions functions) {
        return new EntityAnimations(kind, geometry, library, controllers, functions);
    }

    /**
     * Upstream {@code SingleControllerDiscovery} (hybrid): the controller {@code <category>.<name>} exists when the
     * target declares it, the model overrides it ({@code <category>_ctrl_<name>}), or one of its animations is a
     * non-empty clip.
     */
    boolean declares(String name, String[] animations) {
        String controller = category + "." + name;
        if (controllers.containsKey(controller) || functions.events()
            .contains(ModelFunctions.overrideEvent(controller))) {
            return true;
        }
        if (animations != null) {
            for (String animation : animations) {
                AnimationClip clip = library.get(animation);
                if (clip != null && !clip.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Upstream {@code ParallelControllerDiscovery} (not multi): declared controllers {@code <category>.<base>_[0-7]},
     * overrides {@code <category>_ctrl_<base>_[0-7]}, and every non-empty animation {@code <base>[0-7]} (the default
     * target's too), sorted by controller name. The value is the animation a coded controller loops, or null.
     */
    private Map<String, String> discoverParallel(String base) {
        Map<String, String> names = new TreeMap<>();
        Pattern controller = Pattern.compile("^" + Pattern.quote(category + "." + base + "_") + "[0-7]$");
        for (String name : controllers.keySet()) {
            if (controller.matcher(name)
                .matches()) {
                names.put(name, null);
            }
        }
        Pattern event = Pattern.compile("^" + Pattern.quote(category + "_ctrl_" + base + "_") + "[0-7]$");
        for (String e : functions.events()) {
            if (event.matcher(e)
                .matches()) {
                String name = e.replace("_ctrl_", ".");
                String animation = base + name.substring(name.length() - 1);
                names.put(name, library.contains(animation) ? animation : null);
            }
        }
        Pattern animation = Pattern.compile("^" + Pattern.quote(base) + "[0-7]$");
        for (String name : library.names()) {
            if (animation.matcher(name)
                .matches()) {
                AnimationClip clip = library.get(name);
                if (clip != null && !clip.isEmpty()) {
                    names.put(category + "." + base + "_" + name.substring(base.length()), name);
                }
            }
        }
        return Collections.unmodifiableMap(names);
    }

    @Override
    public int count() {
        return geometry.bones.length;
    }

    @Override
    public int indexOf(String bone) {
        return geometry.indexOf(bone);
    }
}

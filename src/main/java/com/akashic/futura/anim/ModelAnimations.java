/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/ParallelControllerDiscovery.java and
 * src/main/java/com/elfmcys/ysm/client/controller/ArmorControllerDiscovery.java (which controllers a model gets),
 * src/main/java/com/elfmcys/ysm/geckolib3/geo/render/built/GeoLocatorType.java (head locator bones), Apache License
 * 2.0.
 * Changes: only the animation-name discovery; controller and event-handler discovery from files is Tier 2.
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.model.LoadedModel;

/**
 * Everything about a model's animations that does not depend on the player: compiled clips (with the default model
 * behind them), item conditions, which optional controllers exist, bone lookup. Build once per model and share between
 * every {@link PlayerAnimator} using it.
 */
public final class ModelAnimations implements AnimationPlayer.BoneIndex {

    private static final Pattern PRE_PARALLEL = Pattern.compile("^pre_parallel[0-7]$");
    private static final Pattern PARALLEL = Pattern.compile("^parallel[0-7]$");
    private static final ItemConditions.Slot[] ARMOR_ORDER = { ItemConditions.Slot.FEET, ItemConditions.Slot.LEGS,
        ItemConditions.Slot.CHEST, ItemConditions.Slot.HEAD };

    public final BakedGeometry geometry;
    public final AnimationLibrary library;
    public final ItemConditions conditions;
    /** {@link OriginVersion} level of the model. */
    public final int originVersion;
    /** {@code player.pre_parallel_N} controller name to animation name, sorted by controller name. */
    final Map<String, String> preParallel;
    /** {@code player.parallel_N} controller name to animation name, sorted by controller name. */
    final Map<String, String> parallel;
    /** Armour slots that get a controller, in upstream order. */
    final List<ItemConditions.Slot> armorSlots;
    /** Bones of the {@code Head} locator group ({@code Head}, {@code Head2}...), which follow the look. */
    final int[] headBones;
    final float[] initialRotations;

    private ModelAnimations(BakedGeometry geometry, AnimationLibrary library, String originVer) {
        this.geometry = geometry;
        this.library = library;
        this.conditions = new ItemConditions(library.names());
        this.originVersion = OriginVersion.fromModVersion(originVer);
        this.preParallel = discoverParallel(library, PRE_PARALLEL, "pre_parallel");
        this.parallel = discoverParallel(library, PARALLEL, "parallel");
        List<ItemConditions.Slot> slots = new ArrayList<>();
        for (ItemConditions.Slot slot : ARMOR_ORDER) {
            if (conditions.armor.hasTest(slot) || library.contains(slot.id + ":default")) {
                slots.add(slot);
            }
        }
        this.armorSlots = Collections.unmodifiableList(slots);
        List<Integer> heads = new ArrayList<>();
        float[] init = new float[geometry.bones.length * 3];
        for (int i = 0; i < geometry.bones.length; i++) {
            BakedGeometry.Bone bone = geometry.bones[i];
            System.arraycopy(bone.rotation, 0, init, i * 3, 3);
            if ("Head".equals(locatorName(bone.name))) {
                heads.add(i);
            }
        }
        this.headBones = new int[heads.size()];
        for (int i = 0; i < headBones.length; i++) {
            headBones[i] = heads.get(i);
        }
        this.initialRotations = init;
    }

    /** Compiles a model with the built-in default model's animations as fallback (none for the default itself). */
    public static ModelAnimations compile(LoadedModel model) {
        AnimationLibrary fallback = DefaultAnimations.isDefault(model.source) ? null : DefaultAnimations.library();
        return compile(model, fallback, new ClipCompiler());
    }

    /**
     * @param fallback the library missing names fall back to, or null
     * @param compiler compiles the clips (tests stub Molang through it)
     */
    public static ModelAnimations compile(LoadedModel model, AnimationLibrary fallback, ClipCompiler compiler) {
        return new ModelAnimations(
            model.main,
            AnimationLibrary.of(model, fallback, compiler),
            model.manifest.properties.originVer);
    }

    /** For tests: a geometry and a ready library. */
    public static ModelAnimations of(BakedGeometry geometry, AnimationLibrary library, String originVer) {
        return new ModelAnimations(geometry, library, originVer);
    }

    private static Map<String, String> discoverParallel(AnimationLibrary library, Pattern pattern, String base) {
        Map<String, String> names = new TreeMap<>();
        for (String name : library.names()) {
            if (pattern.matcher(name)
                .matches()) {
                AnimationClip clip = library.get(name);
                if (clip != null && !clip.isEmpty()) {
                    names.put(PlayerControllers.CATEGORY + "." + base + "_" + name.substring(base.length()), name);
                }
            }
        }
        return Collections.unmodifiableMap(names);
    }

    /** Upstream {@code GeoLocatorType.getByBoneName}: trailing digits are dropped ({@code Head2} is {@code Head}). */
    static String locatorName(String bone) {
        int end = bone.length();
        while (end > 0 && Character.isDigit(bone.charAt(end - 1))) {
            end--;
        }
        return end > 0 && end < bone.length() ? bone.substring(0, end) : bone;
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

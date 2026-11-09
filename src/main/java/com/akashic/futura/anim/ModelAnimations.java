/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/ParallelControllerDiscovery.java and
 * src/main/java/com/elfmcys/ysm/client/controller/ArmorControllerDiscovery.java (which controllers a model gets),
 * src/main/java/com/elfmcys/ysm/geckolib3/geo/render/built/GeoLocatorType.java (head locator bones) and
 * src/main/java/com/elfmcys/ysm/client/entity/FirstPersonModelState.java (the first-person part roots), Apache License
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

    /** Controller category of the first-person arms (upstream {@code FPArmControllerCollection}). */
    public static final String FIRST_PERSON_CATEGORY = "fp.arm";

    private static final Pattern PRE_PARALLEL = Pattern.compile("^pre_parallel[0-7]$");
    private static final Pattern PARALLEL = Pattern.compile("^parallel[0-7]$");
    private static final ItemConditions.Slot[] ARMOR_ORDER = { ItemConditions.Slot.FEET, ItemConditions.Slot.LEGS,
        ItemConditions.Slot.CHEST, ItemConditions.Slot.HEAD };

    public final BakedGeometry geometry;
    public final AnimationLibrary library;
    public final ItemConditions conditions;
    /** {@link OriginVersion} level of the model. */
    public final int originVersion;
    /** true for the first-person arms ({@code arm} geometry, {@code fp_arm} set), false for the body. */
    public final boolean firstPerson;
    /** First-person part roots ({@code LeftArm}, {@code RightArm}, {@code Background}), or -1 when absent. */
    public final int leftArm;
    public final int rightArm;
    public final int background;
    /** {@code player.pre_parallel_N} controller name to animation name, sorted by controller name. */
    final Map<String, String> preParallel;
    /** {@code player.parallel_N} controller name to animation name, sorted by controller name. */
    final Map<String, String> parallel;
    /** Armour slots that get a controller, in upstream order. */
    final List<ItemConditions.Slot> armorSlots;
    /** Bones of the {@code Head} locator group ({@code Head}, {@code Head2}...), which follow the look. */
    final int[] headBones;
    final float[] initialRotations;

    private ModelAnimations(BakedGeometry geometry, AnimationLibrary library, String originVer, boolean firstPerson) {
        this.geometry = geometry;
        this.library = library;
        this.firstPerson = firstPerson;
        this.conditions = new ItemConditions(library.names());
        this.originVersion = OriginVersion.fromModVersion(originVer);
        String category = firstPerson ? FIRST_PERSON_CATEGORY : PlayerControllers.CATEGORY;
        this.preParallel = firstPerson ? Collections.<String, String>emptyMap()
            : discoverParallel(library, PRE_PARALLEL, category, "pre_parallel");
        this.parallel = discoverParallel(library, PARALLEL, category, "parallel");
        List<ItemConditions.Slot> slots = new ArrayList<>();
        for (ItemConditions.Slot slot : ARMOR_ORDER) {
            if (conditions.armor.hasTest(slot) || library.contains(slot.id + ":default")) {
                slots.add(slot);
            }
        }
        this.armorSlots = Collections.unmodifiableList(slots);
        List<Integer> heads = new ArrayList<>();
        float[] init = new float[geometry.bones.length * 3];
        int left = -1, right = -1, back = -1;
        for (int i = 0; i < geometry.bones.length; i++) {
            BakedGeometry.Bone bone = geometry.bones[i];
            System.arraycopy(bone.rotation, 0, init, i * 3, 3);
            if (!firstPerson && "Head".equals(locatorName(bone.name))) {
                heads.add(i);
            }
            // as upstream: a later bone of the same name wins
            if ("LeftArm".equals(bone.name)) {
                left = i;
            } else if ("RightArm".equals(bone.name)) {
                right = i;
            } else if ("Background".equals(bone.name)) {
                back = i;
            }
        }
        this.leftArm = firstPerson ? left : -1;
        this.rightArm = firstPerson ? right : -1;
        this.background = firstPerson ? back : -1;
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
            model.manifest.properties.originVer,
            false);
    }

    /**
     * Compiles the first-person arms: the {@code arm} geometry with the {@code fp_arm} set, the default model's
     * {@code fp_arm} behind it (none for the default itself). Animate them with {@link PlayerAnimator#firstPerson}.
     */
    public static ModelAnimations compileFirstPerson(LoadedModel model) {
        AnimationLibrary fallback = DefaultAnimations.isDefault(model.source) ? null
            : DefaultAnimations.firstPersonLibrary();
        return compileFirstPerson(model, fallback, new ClipCompiler());
    }

    /**
     * @param fallback the first-person library missing names fall back to, or null
     * @param compiler compiles the clips (tests stub Molang through it)
     */
    public static ModelAnimations compileFirstPerson(LoadedModel model, AnimationLibrary fallback,
        ClipCompiler compiler) {
        return new ModelAnimations(
            model.arm,
            AnimationLibrary.ofFirstPerson(model, fallback, compiler),
            model.manifest.properties.originVer,
            true);
    }

    /** For tests: a geometry and a ready library. */
    public static ModelAnimations of(BakedGeometry geometry, AnimationLibrary library, String originVer) {
        return new ModelAnimations(geometry, library, originVer, false);
    }

    /** For tests: first-person arms from a geometry and a ready library. */
    public static ModelAnimations firstPersonOf(BakedGeometry geometry, AnimationLibrary library, String originVer) {
        return new ModelAnimations(geometry, library, originVer, true);
    }

    private static Map<String, String> discoverParallel(AnimationLibrary library, Pattern pattern, String category,
        String base) {
        Map<String, String> names = new TreeMap<>();
        for (String name : library.names()) {
            if (pattern.matcher(name)
                .matches()) {
                AnimationClip clip = library.get(name);
                if (clip != null && !clip.isEmpty()) {
                    names.put(category + "." + base + "_" + name.substring(base.length()), name);
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

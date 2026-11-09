/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/collections/PlayerControllerCollection.java and the predicates in
 * src/main/java/com/elfmcys/ysm/client/animation/predicate/ (VehiclePredicate, PlayerMainPredicate, MainhandPredicate,
 * OffhandPredicate, SwingPredicate, UsePredicate, PassengerPredicate, CapPredicate, ParallelPredicate,
 * ArmorPredicate, IAnimationPredicate.playCompatAnimation), Apache License 2.0.
 * Changes: Minecraft reads go through PlayerInputs; mod compat controllers and predicates (ParCool, Carry On, TaCZ,
 * Superb Warfare, SlashBlade, Iron's Spells, Create, SWEM) and the GUI-only controllers are not ported; the
 * pre_/post_ controllers that only exist when a model declares them in files are Tier 2.
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.akashic.futura.format.pojo.animation.LoopType;

/**
 * Builds the player controller stack in upstream order:
 *
 * <pre>
 * pre_parallel_0..7 (0 s)  vehicle (0.1 s)  main (0.1 s)  hold_offhand (0.1 s)  hold_mainhand (0.1 s)
 * swing (0 s)  use (0.1 s)  passenger (0.1 s)  cap (0 s)  parallel_0..7 (0 s, additive rotation)
 * armor_feet/legs/chest/head (0 s)
 * </pre>
 *
 * Tier 2 seam: upstream wraps each of these (except cap) in a {@code HybridAnimationController}, which switches to the
 * model's Bedrock controller of the same name ({@code player.main}...) when its {@code animation_controllers} files
 * declare one, and adds {@code pre_main}, {@code post_main}, {@code pre_hold}... controllers that exist only when
 * declared. Those plug in here, by name, without changing the processor.
 */
public final class PlayerControllers {

    public static final String CATEGORY = "player";
    public static final String CAP = CATEGORY + ".cap";

    private PlayerControllers() {}

    static List<AnimationController> build(PlayerAnimator a, ModelAnimations m, float[] committed) {
        List<AnimationController> list = new ArrayList<>();
        AnimationLibrary lib = m.library;
        for (Map.Entry<String, String> e : m.preParallel.entrySet()) {
            String anim = e.getValue();
            list.add(new CodedController(e.getKey(), lib, committed, m, 0, false, c -> loop(c, anim)));
        }
        list.add(new CodedController(CATEGORY + ".vehicle", lib, committed, m, 0.1f, false, c -> vehicle(a, c)));
        list.add(new CodedController(CATEGORY + ".main", lib, committed, m, 0.1f, false, c -> main(a, c)));
        list.add(
            new CodedController(CATEGORY + ".hold_offhand", lib, committed, m, 0.1f, false, c -> holdOffhand(a, c)));
        list.add(
            new CodedController(CATEGORY + ".hold_mainhand", lib, committed, m, 0.1f, false, c -> holdMainhand(a, c)));
        list.add(new CodedController(CATEGORY + ".swing", lib, committed, m, 0, false, c -> swing(a, c)));
        list.add(new CodedController(CATEGORY + ".use", lib, committed, m, 0.1f, false, c -> use(a, c)));
        list.add(new CodedController(CATEGORY + ".passenger", lib, committed, m, 0.1f, false, c -> passenger(a, c)));
        list.add(new CodedController(CAP, lib, committed, m, 0, false, c -> cap(a, c)));
        for (Map.Entry<String, String> e : m.parallel.entrySet()) {
            String anim = e.getValue();
            list.add(new CodedController(e.getKey(), lib, committed, m, 0, true, c -> loop(c, anim)));
        }
        for (ItemConditions.Slot slot : m.armorSlots) {
            list.add(
                new CodedController(
                    CATEGORY + ".armor_" + slot.id,
                    lib,
                    committed,
                    m,
                    0,
                    false,
                    c -> armor(a, c, slot)));
        }
        return list;
    }

    /** {@code ParallelPredicate}: always the same clip, looping. */
    static PlayState loop(CodedController c, String animation) {
        c.setAnimation(animation, LoopType.LOOP);
        return PlayState.CONTINUE;
    }

    /** {@code VehiclePredicate}. */
    static PlayState vehicle(PlayerAnimator a, CodedController c) {
        PlayerInputs in = a.inputs;
        if (!in.isRiding()) {
            return PlayState.STOP;
        }
        String name = a.model.conditions.vehicle.test(in.vehicleId(), in.vehicleTags());
        if (!name.isEmpty()) {
            c.setAnimation(name, LoopType.LOOP);
            return PlayState.CONTINUE;
        }
        PlayerInputs.VehicleKind kind = in.vehicleKind();
        if (kind == PlayerInputs.VehicleKind.PIG) {
            c.setAnimation("ride_pig", LoopType.LOOP);
        } else if (kind == PlayerInputs.VehicleKind.SADDLEABLE) {
            c.setAnimation("ride", LoopType.LOOP);
        } else if (kind == PlayerInputs.VehicleKind.BOAT) {
            c.setAnimation("boat", LoopType.LOOP);
        } else {
            c.setAnimation("sit", LoopType.LOOP);
        }
        return PlayState.CONTINUE;
    }

    /** {@code PlayerMainPredicate}: nothing while riding (the vehicle controller handles it). */
    static PlayState main(PlayerAnimator a, CodedController c) {
        if (a.inputs.isRiding()) {
            return PlayState.STOP;
        }
        String state = MainStates.select(a.inputs, a.limbSwingAmount, false);
        c.setAnimation(state, MainStates.loop(state));
        return PlayState.CONTINUE;
    }

    /** {@code MainhandPredicate}. */
    static PlayState holdMainhand(PlayerAnimator a, CodedController c) {
        PlayerInputs in = a.inputs;
        if (in.isSwinging() && !in.isSwingingOffhand() || in.isUsingItem() && !in.isUsingOffhand()) {
            return PlayState.PAUSE;
        }
        if (in.hasChargedCrossbow(false)) {
            return playCompat(a, c, "hold_mainhand:charged_crossbow", LoopType.LOOP);
        }
        if (in.isFishing()) {
            return playCompat(a, c, "hold_mainhand:fishing", LoopType.LOOP);
        }
        ItemInfo item = in.mainhand();
        if (!item.sameStack(a.heldMainhand)) {
            a.heldMainhand = item;
            c.indicateReload();
        }
        String name = a.model.conditions.holdMainhand.test(item);
        if (!name.isEmpty()) {
            return playCompat(a, c, name, LoopType.LOOP);
        }
        return PlayState.STOP;
    }

    /** {@code OffhandPredicate}: on 1.7.10 the offhand is always empty, so only {@code hold_offhand:empty}. */
    static PlayState holdOffhand(PlayerAnimator a, CodedController c) {
        PlayerInputs in = a.inputs;
        if (in.isSwinging() && in.isSwingingOffhand() || in.isUsingItem() && in.isUsingOffhand()) {
            return PlayState.PAUSE;
        }
        if (in.hasChargedCrossbow(true)) {
            return playCompat(a, c, "hold_offhand:charged_crossbow", LoopType.LOOP);
        }
        ItemInfo item = in.offhand();
        if (!item.sameStack(a.heldOffhand)) {
            a.heldOffhand = item;
            c.indicateReload();
        }
        String name = a.model.conditions.holdOffhand.test(item);
        if (!name.isEmpty()) {
            return playCompat(a, c, name, LoopType.LOOP);
        }
        return PlayState.STOP;
    }

    /** {@code SwingPredicate}: restarts on the first tick of each swing, then plays to its end. */
    static PlayState swing(PlayerAnimator a, CodedController c) {
        PlayerInputs in = a.inputs;
        if (in.isSwinging() && !in.isSleeping()) {
            if (in.swingTime() == 0 && a.setEntityTickState(PlayerAnimator.TICK_SWING)) {
                c.indicateReload();
            }
            boolean off = in.isSwingingOffhand();
            ItemConditions.Hand condition = off ? a.model.conditions.swingOffhand : a.model.conditions.swingMainhand;
            String name = condition.test(off ? in.offhand() : in.mainhand());
            if (!name.isEmpty()) {
                return playCompat(a, c, name, LoopType.PLAY_ONCE);
            }
            return playCompat(a, c, off ? "swing_offhand" : "swing_hand", LoopType.PLAY_ONCE);
        }
        return PlayState.CONTINUE;
    }

    /** {@code UsePredicate}: restarts on the first tick of each use. */
    static PlayState use(PlayerAnimator a, CodedController c) {
        PlayerInputs in = a.inputs;
        if (in.isUsingItem() && !in.isSleeping()) {
            if (in.ticksUsingItem() == 1 && a.setEntityTickState(PlayerAnimator.TICK_USE)) {
                c.indicateReload();
            }
            if (!in.isUsingOffhand()) {
                String name = a.model.conditions.useMainhand.test(in.mainhand());
                return playCompat(a, c, name.isEmpty() ? "use_mainhand" : name, LoopType.LOOP);
            }
            String name = a.model.conditions.useOffhand.test(in.offhand());
            return playCompat(a, c, name.isEmpty() ? "use_offhand" : name, LoopType.LOOP);
        }
        return PlayState.STOP;
    }

    /** {@code PassengerPredicate}: only {@code passenger$<id>} / {@code passenger#<tag>} names. */
    static PlayState passenger(PlayerAnimator a, CodedController c) {
        String passenger = a.inputs.passengerId();
        if (passenger == null) {
            return PlayState.STOP;
        }
        String name = a.model.conditions.passenger.test(passenger, a.inputs.passengerTags());
        if (!name.isEmpty()) {
            c.setAnimation(name, LoopType.LOOP);
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    /** {@code CapPredicate}: the extra (emote) animation, with its own loop type. */
    static PlayState cap(PlayerAnimator a, CodedController c) {
        if (a.playingExtra) {
            if (a.extraDirty) {
                a.extraDirty = false;
                c.indicateReload();
            }
            c.setAnimation(a.extraName);
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    /** {@code ArmorPredicate}. */
    static PlayState armor(PlayerAnimator a, CodedController c, ItemConditions.Slot slot) {
        ItemInfo item = a.inputs.armor(slot);
        if (item == null || item.isEmpty()) {
            return PlayState.STOP;
        }
        String name = a.model.conditions.armor.test(slot, item);
        if (!name.isEmpty()) {
            c.setAnimation(name, LoopType.LOOP);
            return PlayState.CONTINUE;
        }
        String fallback = slot.id + ":default";
        if (a.model.library.get(fallback) != null) {
            c.setAnimation(fallback, LoopType.LOOP);
            return PlayState.CONTINUE;
        }
        return PlayState.STOP;
    }

    /**
     * {@code IAnimationPredicate.playCompatAnimation}: models older than {@link OriginVersion#FILE_HAND_LOOP} get the
     * forced loop type unless the clip is borrowed from the default model.
     */
    static PlayState playCompat(PlayerAnimator a, CodedController c, String name, LoopType loop) {
        int origin = a.model.originVersion;
        boolean fallback = false;
        if (origin < OriginVersion.FILE_HAND_LOOP) {
            AnimationLibrary.Resolution r = a.model.library.resolve(name);
            fallback = r != null && r.fallback;
        }
        if (OriginVersion.usesFileHandLoop(origin, fallback)) {
            c.setAnimation(name);
        } else {
            c.setAnimation(name, loop);
        }
        return PlayState.CONTINUE;
    }
}

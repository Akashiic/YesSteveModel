/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/collections/PlayerControllerCollection.java and the predicates in
 * src/main/java/com/elfmcys/ysm/client/animation/predicate/ (VehiclePredicate, PlayerMainPredicate, MainhandPredicate,
 * OffhandPredicate, SwingPredicate, UsePredicate, PassengerPredicate, CapPredicate, ParallelPredicate,
 * ArmorPredicate, IAnimationPredicate.playCompatAnimation), Apache License 2.0.
 * Changes: Minecraft reads go through PlayerInputs; mod compat controllers and predicates (ParCool, Carry On, TaCZ,
 * Superb Warfare, SlashBlade, Iron's Spells, Create, SWEM) and the GUI-only controllers are not ported.
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
 * pre_parallel_* (0 s)  vehicle (0.1 s)
 * pre_main*  main (0.1 s)  post_main*
 * pre_hold*  hold_offhand (0.1 s)  hold_mainhand (0.1 s)  post_hold*
 * pre_swing*  swing (0 s)  post_swing*
 * pre_use*  use (0.1 s)  post_use*
 * passenger (0.1 s)  cap (0 s)  parallel_* (0 s, additive rotation)  armor_mainhand..head (0 s)
 * </pre>
 *
 * Every controller but {@code cap} is a {@link HybridController}: a Bedrock controller of the same name in the model's
 * files replaces the coded predicate. The starred ones exist only when the model declares them (a Bedrock controller,
 * or an override function {@code player_ctrl_<name>}); their coded predicate stops. Every coded predicate can be
 * overridden by the model's {@code player_ctrl_<name>} function.
 */
public final class PlayerControllers {

    public static final String CATEGORY = "player";
    public static final String CAP = CATEGORY + ".cap";

    private PlayerControllers() {}

    static List<AnimationController> build(PlayerAnimator a, ModelAnimations m, float[] committed) {
        Builder b = new Builder(m, committed);
        for (Map.Entry<String, String> e : m.preParallel.entrySet()) {
            String anim = e.getValue();
            b.hybrid(e.getKey(), 0, false, anim != null ? c -> loop(c, anim) : Builder.EMPTY);
        }
        b.hybrid(CATEGORY + ".vehicle", 0.1f, false, c -> vehicle(a, c));
        b.multi("pre_main");
        b.hybrid(CATEGORY + ".main", 0.1f, false, c -> main(a, c));
        b.multi("post_main");
        b.multi("pre_hold");
        b.hybrid(CATEGORY + ".hold_offhand", 0.1f, false, c -> holdOffhand(a, c));
        b.hybrid(CATEGORY + ".hold_mainhand", 0.1f, false, c -> holdMainhand(a, c));
        b.multi("post_hold");
        b.multi("pre_swing");
        b.hybrid(CATEGORY + ".swing", 0, false, c -> swing(a, c));
        b.multi("post_swing");
        b.multi("pre_use");
        b.hybrid(CATEGORY + ".use", 0.1f, false, c -> use(a, c));
        b.multi("post_use");
        b.hybrid(CATEGORY + ".passenger", 0.1f, false, c -> passenger(a, c));
        b.coded(CAP, 0, false, c -> cap(a, c));
        for (Map.Entry<String, String> e : m.parallel.entrySet()) {
            String anim = e.getValue();
            b.hybrid(e.getKey(), 0, true, anim != null ? c -> loop(c, anim) : Builder.EMPTY);
        }
        for (ItemConditions.Slot slot : m.armorSlots) {
            b.hybrid(CATEGORY + ".armor_" + slot.id, 0, false, c -> armor(a, c, slot));
        }
        return b.list;
    }

    /** Makes the controllers of one stack: coded ones with their override, hybrids with their Bedrock definition. */
    static final class Builder {

        /** {@code EmptyPredicate}: always stops. */
        static final CodedController.Predicate EMPTY = c -> PlayState.STOP;

        final List<AnimationController> list = new ArrayList<>();
        private final ModelAnimations m;
        private final float[] committed;
        private final String category;

        Builder(ModelAnimations m, float[] committed) {
            this.m = m;
            this.committed = committed;
            this.category = m.firstPerson ? FirstPersonControllers.CATEGORY : CATEGORY;
        }

        CodedController newCoded(String name, float transitionSeconds, boolean blendRotation,
            CodedController.Predicate predicate) {
            CodedController c = new CodedController(
                name,
                m.library,
                committed,
                m,
                transitionSeconds,
                blendRotation,
                predicate);
            c.setOverride(m.functions.first(ModelFunctions.overrideEvent(name)));
            return c;
        }

        void coded(String name, float transitionSeconds, boolean blendRotation, CodedController.Predicate predicate) {
            list.add(newCoded(name, transitionSeconds, blendRotation, predicate));
        }

        void hybrid(String name, float transitionSeconds, boolean blendRotation, CodedController.Predicate predicate) {
            CodedController coded = newCoded(name, transitionSeconds, blendRotation, predicate);
            ControllerData data = m.controllers.get(name);
            BedrockController bedrock = data == null ? null
                : new BedrockController(name, m.library, committed, m, transitionSeconds, m.controllers::get, data);
            list.add(new HybridController(coded, bedrock));
        }

        /** {@code MultiControllerDiscovery}: declared ones only, with an always-stopping predicate. */
        void multi(String base) {
            for (String name : m.discoverMulti(category, base)) {
                hybrid(name, 0, false, EMPTY);
            }
        }
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
        ItemInfo item = slot == ItemConditions.Slot.MAINHAND ? a.inputs.mainhand()
            : slot == ItemConditions.Slot.OFFHAND ? a.inputs.offhand() : a.inputs.armor(slot);
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

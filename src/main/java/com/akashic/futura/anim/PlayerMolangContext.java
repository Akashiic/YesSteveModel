/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/expression/host/builtin/EntityBuiltins.java, ModelBuiltins.java,
 * ControlReadBuiltins.java, ControlBuiltins.java, PlayerBuiltins.java and PositionComponent.java, Apache License 2.0;
 * plus the query names the legacy 1.1.5 registered (AnimationRegister.setParserValue, BSD-3-Clause) that 2.x no longer
 * lists, so older models keep working.
 * Changes: one switch over names reading PlayerInputs; particles, keyboard and mod_version answer as no-ops; effects
 * (bone colour, glow, transparency, sounds) apply directly instead of through an effect batch; user functions
 * ({@code fn.*}, {@code args[i]}) are called on the same context with an argument stack.
 */
package com.akashic.futura.anim;

import java.util.Random;

import com.akashic.futura.molang.MolangExpression;
import com.akashic.futura.molang.MolangValue;
import com.akashic.futura.molang.MolangVariables;

/**
 * Answers the {@code query.*}, {@code ysm.*}, {@code ctrl.*}, {@code fn.*} (and {@code math.random*}) names for one
 * player. Unknown names return null (the expression sees 0).
 */
public final class PlayerMolangContext extends AnimationContext {

    /** Nested user-function calls allowed (upstream rejects recursion when compiling; here it is cut off). */
    static final int MAX_FUNCTION_DEPTH = 16;
    private static final MolangValue[] NO_ARGS = new MolangValue[0];

    private final PlayerAnimator a;
    private final Random random = new Random();
    /** The arguments of the user functions and handlers running, innermost last. */
    private final MolangValue[][] argStack = new MolangValue[MAX_FUNCTION_DEPTH + 1][];
    private int depth;

    PlayerMolangContext(PlayerAnimator animator, MolangVariables variables) {
        super(variables);
        this.a = animator;
    }

    @Override
    public MolangValue call(String namespace, String name, MolangValue[] args) {
        if (namespace == null || name == null) {
            return null;
        }
        if (a.inputs == null) {
            return null;
        }
        switch (namespace) {
            case "query":
                return query(name, args);
            case "ysm":
                return ysm(name, args);
            case "ctrl":
                return ctrl(name, args);
            case "math":
                return math(name, args);
            case "fn":
                return userFunction(name, args);
            default:
                return null;
        }
    }

    /** {@code args[i]}: an argument of the innermost user function or event handler. */
    @Override
    public MolangValue index(String namespace, String name, int index) {
        if (!"global".equals(namespace) || !"args".equals(name) || depth == 0) {
            return null;
        }
        MolangValue[] args = argStack[depth - 1];
        return index >= 0 && index < args.length ? args[index] : null;
    }

    /** {@code fn.<name>(args...)}: the model's function, its result (missing when unknown or nested too deep). */
    private MolangValue userFunction(String name, MolangValue[] args) {
        MolangExpression body = a.model.functions.function(name);
        if (body == null) {
            return null;
        }
        return invoke(body, args.clone());
    }

    /**
     * Runs a user function or event handler with its arguments ({@code args[i]}); its {@code t.*} are its own.
     *
     * @return its value, or null when calls are nested too deep
     */
    MolangValue invoke(MolangExpression body, MolangValue[] args) {
        if (depth >= MAX_FUNCTION_DEPTH) {
            return null;
        }
        argStack[depth++] = args == null ? NO_ARGS : args;
        try {
            return body.evaluate(this);
        } catch (RuntimeException e) {
            return null;
        } finally {
            argStack[--depth] = null;
        }
    }

    private MolangValue query(String name, MolangValue[] args) {
        PlayerInputs in = a.inputs;
        switch (name) {
            case "anim_time":
                return num(animTime);
            case "life_time":
                return num(a.lifeTime());
            case "all_animations_finished":
                return MolangValue.of(controller != null && controller.allAnimationsFinished());
            case "any_animation_finished":
                return MolangValue.of(controller != null && controller.anyAnimationFinished());
            case "body_y_rotation":
                return num(AnimMath.wrapDegrees(a.lerpBodyRot));
            case "body_x_rotation":
                return num(in.pitch());
            case "cardinal_facing_2d":
                return num(cardinalFacing(in.yaw()));
            case "eye_target_y_rotation":
                return num(in.yaw());
            case "eye_target_x_rotation":
                return num(in.pitch());
            case "ground_speed":
                return num(20 * (float) Math.sqrt(in.motionX() * in.motionX() + in.motionZ() * in.motionZ()));
            // upstream quirk: x is the yaw and y the pitch
            case "head_x_rotation":
                return num(a.netHeadYaw);
            case "head_y_rotation":
                return num(a.headPitch);
            case "health":
                return num(in.health());
            case "max_health":
                return num(in.maxHealth());
            case "is_eating":
                return MolangValue.of(in.isUsingItem() && in.usedItem().useAction == ItemInfo.UseAction.EAT);
            case "is_in_water":
                return MolangValue.of(in.isInWater());
            case "is_in_water_or_rain":
                return MolangValue.of(in.isInWaterOrRain());
            case "is_jumping":
                return MolangValue.of(!in.isFlying() && !in.isRiding() && !in.onGround() && !in.isInWater());
            case "is_on_ground":
                return MolangValue.of(in.onGround());
            case "is_sneaking":
                return MolangValue.of(in.onGround() && in.isSneaking());
            case "is_swimming":
                return MolangValue.of(in.isSwimming());
            case "time_stamp":
                return num(in.worldTime());
            case "yaw_speed":
                return num(in.yawSpeed());
            case "position":
                return positionValue(args, false);
            case "position_delta":
                return positionValue(args, true);
            case "vertical_speed":
                return num(a.renderTickDelta == 0 ? 0 : 20 * (float) a.positionDelta[1] / a.renderTickDelta);
            case "is_item_name_any":
                return isItemNameAny(args);
            // legacy (1.1.5) names
            case "distance_from_camera":
                return num(in.distanceFromCamera());
            case "equipment_count": {
                int count = 0;
                for (ItemConditions.Slot slot : ItemConditions.Slot.values()) {
                    if (slot.isArmor() && !in.armor(slot)
                        .isEmpty()) {
                        count++;
                    }
                }
                return num(count);
            }
            case "has_cape":
                return MolangValue.of(in.hasCape());
            case "has_rider":
                return MolangValue.of(in.passengerId() != null);
            case "hurt_time":
                return num(in.hurtTime());
            case "is_first_person":
                return MolangValue.of(in.isFirstPerson());
            case "is_on_fire":
                return MolangValue.of(in.isOnFire());
            case "is_playing_dead":
                return MolangValue.of(in.isDeadOrDying());
            case "is_riding":
                return MolangValue.of(in.isRiding());
            case "is_sleeping":
                return MolangValue.of(in.isSleeping());
            case "is_spectator":
                return MolangValue.ZERO;
            case "is_sprinting":
                return MolangValue.of(in.isSprinting());
            case "is_using_item":
                return MolangValue.of(in.isUsingItem());
            case "item_in_use_duration":
                return num(in.isUsingItem() ? in.ticksUsingItem() / 20f : 0);
            case "item_max_use_duration":
                return num(in.isUsingItem() ? in.usedItem().maxUseDuration / 20f : 0);
            case "item_remaining_use_duration":
                return num(in.isUsingItem() ? in.itemUseRemainingTicks() / 20f : 0);
            case "modified_distance_moved":
                return num(in.distanceWalkedModified());
            case "moon_phase":
                return num(in.moonPhase());
            case "player_level":
                return num(in.experienceLevel());
            case "time_of_day":
                return num(normalizeTime(in.worldTime()));
            case "walk_distance":
                return num(in.distanceWalkedOnStepModified());
            default:
                return null;
        }
    }

    private MolangValue ysm(String name, MolangValue[] args) {
        PlayerInputs in = a.inputs;
        switch (name) {
            case "is_close_eyes": {
                float remainder = (a.seekTime + Math.abs(in.uuidLeastSignificantBits()) % 10) % 90;
                return MolangValue.of(in.isSleeping() || 85 < remainder && remainder < 90);
            }
            case "is_fishing":
                return MolangValue.of(in.isFishing());
            case "is_open_air":
                return MolangValue.of(in.isOpenAir());
            case "weather":
                return num(in.weather());
            case "elytra_rot_x":
            case "elytra_rot_y":
            case "elytra_rot_z":
                return MolangValue.ZERO;
            case "fps":
                return num(in.fps());
            case "head_pitch":
                return num(a.headPitch);
            case "head_yaw":
                return num(a.netHeadYaw);
            case "rendering_in_inventory":
                return MolangValue.of(in.isRenderingInInventory());
            case "texture_name":
                return MolangValue.of(in.textureName());
            case "yya":
                return num(in.moveForward());
            case "ground_speed2": {
                double dx = a.positionDelta[0], dz = a.positionDelta[2];
                return num(a.renderTickDelta == 0 ? 0 : 20 * (float) Math.sqrt(dx * dx + dz * dz) / a.renderTickDelta);
            }
            case "input_horizontal":
                return num(relativeMoveInput(false));
            case "input_vertical":
                return num(relativeMoveInput(true));
            case "food_level":
                return num(in.foodLevel());
            case "armor_value":
                return num(in.armorValue());
            case "has_helmet":
                return MolangValue.of(
                    !in.armor(ItemConditions.Slot.HEAD)
                        .isEmpty());
            case "has_chest_plate":
                return MolangValue.of(
                    !in.armor(ItemConditions.Slot.CHEST)
                        .isEmpty());
            case "has_leggings":
                return MolangValue.of(
                    !in.armor(ItemConditions.Slot.LEGS)
                        .isEmpty());
            case "has_boots":
                return MolangValue.of(
                    !in.armor(ItemConditions.Slot.FEET)
                        .isEmpty());
            case "has_mainhand":
                return MolangValue.of(
                    !in.mainhand()
                        .isEmpty());
            case "has_offhand":
                return MolangValue.of(
                    !in.offhand()
                        .isEmpty());
            case "has_elytra":
            case "in_shield_block_cooldown":
            case "is_riptide":
            case "first_person_mod_hide":
                return MolangValue.ZERO;
            case "mainhand_charged_crossbow":
                return MolangValue.of(in.hasChargedCrossbow(false));
            case "offhand_charged_crossbow":
                return MolangValue.of(in.hasChargedCrossbow(true));
            case "shoot_item_id":
                return MolangValue.of("");
            case "is_passenger":
                return MolangValue.of(in.isRiding());
            case "is_sleep":
                return MolangValue.of(in.isSleeping());
            case "is_sneak":
                return MolangValue.of(in.onGround() && in.isSneaking());
            case "hurt_time":
                return num(in.hurtTime());
            case "bone_rot":
                return boneRotation(args);
            case "bone_pivot_abs":
                return MolangValue.vector(0, 0, 0);
            case "bone_glow":
                if (args.length >= 2) {
                    a.attributes()
                        .setGlow(str(args, 0), f(args, 1));
                }
                return MolangValue.ZERO;
            case "bone_transparency":
                if (args.length >= 2) {
                    a.attributes()
                        .setTransparency(str(args, 0), f(args, 1));
                }
                return MolangValue.ZERO;
            case "bone_color":
                if (args.length >= 4) {
                    a.attributes()
                        .setColor(str(args, 0), f(args, 1), f(args, 2), f(args, 3));
                }
                return MolangValue.ZERO;
            case "first_order":
            case "second_order": {
                if (args.length < 2) {
                    return MolangValue.ZERO;
                }
                float x = f(args, 1);
                float p1 = args.length > 2 ? f(args, 2) : 1;
                float p2 = args.length > 3 ? f(args, 3) : 1;
                float p3 = args.length > 4 ? f(args, 4) : 1;
                return num(a.physics.call(str(args, 0), x, name.equals("first_order"), p1, p2, p3));
            }
            case "play_sound":
                playSound(args);
                return MolangValue.ZERO;
            case "stop_sound":
                if (args.length >= 1) {
                    soundScope(args.length > 1 && args[1].asBoolean()).stop(SoundScope.id(args[0]));
                }
                return MolangValue.ZERO;
            case "stop_all_sounds":
                soundScope(args.length > 0 && args[0].asBoolean()).stopAll();
                return MolangValue.ZERO;
            case "sync":
                if (args.length <= ModelFunctions.SYNC_MAX_ARGUMENTS) {
                    a.sync(floats(args, 0));
                }
                return MolangValue.ZERO;
            case "defer":
                if (args.length >= 1 && args.length <= ModelFunctions.DEFER_MAX_ARGUMENTS) {
                    a.defer(floats(args, 0));
                }
                return MolangValue.ZERO;
            // effects without a backend yet: known names, no effect
            case "particle":
            case "abs_particle":
                return MolangValue.ZERO;
            case "keyboard":
                return MolangValue.ZERO;
            case "mod_version":
                return MolangValue.of("");
            default:
                return null;
        }
    }

    private MolangValue ctrl(String name, MolangValue[] args) {
        switch (name) {
            case "attacked":
            case "death":
            case "elytra_fly":
            case "fly":
            case "idle":
            case "jump":
            case "ladder_down":
            case "ladder_stillness":
            case "ladder_up":
            case "run":
            case "sleep":
            case "sneak":
            case "sneaking":
            case "swim":
            case "swim_stand":
            case "walk":
                return MolangValue.of(name.equals(a.selectedControl()));
            case "playing_extra_animation":
                return MolangValue.of(a.playingExtra && a.capState() != PlaybackState.IDLE);
            case "slashblade_animation": {
                // upstream SlashBladeBuiltinComponent: the combo animation, or "" (phase 5b, external states)
                String combo = SlashBladeStates.holds(a.inputs) ? SlashBladeStates.combo(a.inputs) : null;
                return MolangValue.of(combo == null ? "" : combo);
            }
            case "fishing_animation": {
                // not upstream: the rod's clip (FishingStates), or ""
                String clip = a.fishing.phase().animation;
                return MolangValue.of(clip == null ? "" : clip);
            }
            case "fishing_tension":
                return num(a.fishing.tension());
            case "fishing_heave":
                return num(a.fishing.heave());
            case "state_bypass":
                return num(5);
            case "state_continue":
                return num(2);
            case "state_stop":
                return num(3);
            case "armor":
                return itemCheck(args, Check.ARMOR);
            case "hold":
                return itemCheck(args, Check.HOLD);
            case "swing":
                return itemCheck(args, Check.SWING);
            case "use":
                return itemCheck(args, Check.USE);
            case "indicate_reload":
                if (controller instanceof CodedController) {
                    ((CodedController) controller).indicateReload();
                }
                return MolangValue.ZERO;
            case "reset":
                if (controller instanceof CodedController) {
                    ((CodedController) controller).reset();
                }
                return MolangValue.ZERO;
            case "set_animation":
                if (controller instanceof CodedController && args.length >= 1 && !str(args, 0).isEmpty()) {
                    int loop = args.length > 1 ? (int) f(args, 1) : 0;
                    ((CodedController) controller).setAnimation(str(args, 0), loopOf(loop));
                }
                return MolangValue.ZERO;
            case "set_beginning_transition_length":
                if (controller instanceof CodedController && args.length >= 1 && f(args, 0) >= 0) {
                    ((CodedController) controller).setBeginningTransitionSeconds(f(args, 0));
                }
                return MolangValue.ZERO;
            default:
                return null;
        }
    }

    /**
     * Upstream {@code ModelBuiltins.playSound}: {@code ysm.play_sound(id, name, mode = 0, volume = 1, pitch = 1)}.
     * Mode bits: 1 replaces a sound still playing under the id, 2 uses the player's global scope, 4 loops.
     */
    private void playSound(MolangValue[] args) {
        if (args.length < 2) {
            return;
        }
        Object id = SoundScope.id(args[0]);
        String sound = str(args, 1);
        int mode = args.length > 2 ? (int) f(args, 2) : 0;
        if (!SoundScope.playable(id) || sound.trim()
            .isEmpty() || mode < 0 || mode > 7) {
            return;
        }
        float volume = AnimMath.clamp(args.length > 3 ? f(args, 3) : 1, 0.001f, 1000f);
        float pitch = AnimMath.clamp(args.length > 4 ? f(args, 4) : 1, 0.001f, 1000f);
        soundScope((mode & 2) != 0).play(soundBackend(), id, sound, (mode & 1) != 0, volume, pitch, (mode & 4) != 0);
    }

    private static float[] floats(MolangValue[] args, int from) {
        float[] values = new float[Math.max(0, args.length - from)];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) args[from + i].asNumber();
        }
        return values;
    }

    /** {@code math.random} / {@code math.random_integer}, should the interpreter ask. */
    private MolangValue math(String name, MolangValue[] args) {
        if (args.length < 2) {
            return null;
        }
        switch (name) {
            case "random": {
                float min = f(args, 0), max = f(args, 1);
                if (min > max) {
                    float t = min;
                    min = max;
                    max = t;
                }
                return num(AnimMath.finite(min + random.nextFloat() * (max - min)));
            }
            case "random_integer": {
                int lower = (int) f(args, 0), upper = (int) f(args, 1);
                if (lower > upper) {
                    int t = lower;
                    lower = upper;
                    upper = t;
                }
                return num(upper > lower ? lower + random.nextInt(upper - lower) : lower);
            }
            default:
                return null;
        }
    }

    private static com.akashic.futura.format.pojo.animation.LoopType loopOf(int value) {
        switch (value) {
            case 10:
                return com.akashic.futura.format.pojo.animation.LoopType.LOOP;
            case 11:
                return com.akashic.futura.format.pojo.animation.LoopType.PLAY_ONCE;
            case 12:
                return com.akashic.futura.format.pojo.animation.LoopType.HOLD_ON_LAST_FRAME;
            default:
                return null;
        }
    }

    private enum Check {
        ARMOR,
        HOLD,
        SWING,
        USE
    }

    /** {@code ctrl.hold/swing/use/armor(slot, filter)}: {@code empty}, {@code $id}, {@code #tag}, {@code :class}. */
    private MolangValue itemCheck(MolangValue[] args, Check check) {
        if (args.length < 2) {
            return null;
        }
        PlayerInputs in = a.inputs;
        ItemConditions.Slot slot = ItemConditions.Slot.byId(str(args, 0));
        if (slot == null || (check == Check.ARMOR) != slot.isArmor()) {
            return null;
        }
        String input = str(args, 1);
        if (input.trim()
            .isEmpty()) {
            return MolangValue.ZERO;
        }
        if (check == Check.SWING && (!in.isSwinging() || in.isSleeping())
            || check == Check.USE && (!in.isUsingItem() || in.isSleeping())) {
            return MolangValue.ZERO;
        }
        ItemInfo item = item(slot);
        if (item.isEmpty() && input.equals("empty")) {
            return MolangValue.ONE;
        }
        if (input.length() < 2) {
            return MolangValue.ZERO;
        }
        String value = input.substring(1);
        if (input.startsWith("$")) {
            return MolangValue.of(!item.isEmpty() && value.equals(item.id));
        }
        if (input.startsWith("#")) {
            String tag = ItemConditions.normalizeId(value, true);
            return MolangValue.of(tag != null && item.tags.contains(tag));
        }
        if (check != Check.ARMOR && input.startsWith(":")) {
            return MolangValue.of(
                !item.classify.isEmpty() && item.classify.equals(value) || item.useAction.id()
                    .equals(value));
        }
        return MolangValue.ZERO;
    }

    private ItemInfo item(ItemConditions.Slot slot) {
        switch (slot) {
            case MAINHAND:
                return a.inputs.mainhand();
            case OFFHAND:
                return a.inputs.offhand();
            default:
                return a.inputs.armor(slot);
        }
    }

    private MolangValue isItemNameAny(MolangValue[] args) {
        if (args.length < 2) {
            return null;
        }
        ItemConditions.Slot slot = ItemConditions.Slot.byId(str(args, 0));
        if (slot == null) {
            return null;
        }
        ItemInfo item = item(slot);
        String actual = item.normalizedId;
        if (item.isEmpty() || actual == null) {
            return MolangValue.ZERO;
        }
        for (int i = 1; i < args.length; i++) {
            String expected = ItemConditions.normalizeId(str(args, i), false);
            if (expected == null) {
                return null;
            }
            if (actual.equals(expected)) {
                return MolangValue.ONE;
            }
        }
        return MolangValue.ZERO;
    }

    private MolangValue positionValue(MolangValue[] args, boolean delta) {
        if (args.length < 1) {
            return null;
        }
        int axis = (int) f(args, 0);
        if (axis < 0 || axis > 2) {
            return delta ? num(Float.NaN) : null;
        }
        if (delta) {
            return num((float) a.positionDelta[axis]);
        }
        PlayerInputs in = a.inputs;
        double pt = in.partialTicks();
        double v;
        if (axis == 0) {
            v = in.prevX() + (in.x() - in.prevX()) * pt;
        } else if (axis == 1) {
            v = in.prevY() + (in.y() - in.prevY()) * pt;
        } else {
            v = in.prevZ() + (in.z() - in.prevZ()) * pt;
        }
        return num((float) v);
    }

    /** {@code ysm.bone_rot}: the bone's rotation in file degrees (x and y negated back). */
    private MolangValue boneRotation(MolangValue[] args) {
        if (args.length < 1) {
            return MolangValue.vector(0, 0, 0);
        }
        int bone = a.model.indexOf(str(args, 0));
        if (bone < 0) {
            return MolangValue.vector(0, 0, 0);
        }
        AnimationProcessor p = a.processor();
        float x = p.committedRotation(bone, 0) + p.initialRotation(bone, 0);
        float y = p.committedRotation(bone, 1) + p.initialRotation(bone, 1);
        float z = p.committedRotation(bone, 2) + p.initialRotation(bone, 2);
        return MolangValue.vector(-x * AnimMath.RAD_TO_DEG, -y * AnimMath.RAD_TO_DEG, z * AnimMath.RAD_TO_DEG);
    }

    /** Upstream {@code PositionComponent.relativeMoveInput}. */
    private float relativeMoveInput(boolean vertical) {
        double dx = a.positionDelta[0], dz = a.positionDelta[2];
        if (Math.sqrt(dx * dx + dz * dz) < 1.0E-4) {
            return 0;
        }
        float moveAngle = (float) Math.atan2(dz, dx) * AnimMath.RAD_TO_DEG;
        float entityYaw = 90 - AnimMath.wrapDegrees(-a.inputs.yaw());
        float relative = AnimMath.wrapDegrees(moveAngle - entityYaw);
        return vertical ? (float) Math.cos(relative * AnimMath.DEG_TO_RAD)
            : (float) Math.sin(relative * AnimMath.DEG_TO_RAD);
    }

    /** {@code Direction.get3DDataValue} of the horizontal facing: south 3, west 4, north 2, east 5. */
    static int cardinalFacing(float yaw) {
        int horizontal = (int) Math.floor(yaw / 90.0 + 0.5) & 3;
        switch (horizontal) {
            case 0:
                return 3;
            case 1:
                return 4;
            case 2:
                return 2;
            default:
                return 5;
        }
    }

    /** Legacy {@code MolangUtils.normalizeTime}: {@code ((time + 6000) / 24000) % 1}. */
    static float normalizeTime(long time) {
        return ((float) (time + 6000L) / 24000) % 1;
    }

    private static MolangValue num(float v) {
        return MolangValue.of((double) v);
    }

    private static MolangValue num(double v) {
        return MolangValue.of(v);
    }

    private static float f(MolangValue[] args, int i) {
        return i < args.length && args[i] != null ? (float) args[i].asNumber() : 0;
    }

    private static String str(MolangValue[] args, int i) {
        return i < args.length && args[i] != null ? args[i].asString() : "";
    }
}

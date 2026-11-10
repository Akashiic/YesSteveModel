/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/expression/host/builtin/EntityBuiltins.java, ModelBuiltins.java,
 * ControlReadBuiltins.java (shoot_item_id), ControlBuiltins.java and PositionComponent.java, Apache License 2.0.
 * Changes: the names that apply to a projectile or vehicle subject, in one switch over an EntitySnapshot; player-only
 * names (items, armour, ctrl.* states, sync) answer as the upstream builtins do for a non-player subject (false, 0 or
 * nothing); particles answer as no-ops.
 */
package com.akashic.futura.anim;

import java.util.Random;

import com.akashic.futura.molang.MolangExpression;
import com.akashic.futura.molang.MolangValue;
import com.akashic.futura.molang.MolangVariables;

/**
 * Answers the {@code query.*}, {@code ysm.*}, {@code ctrl.*}, {@code fn.*} (and {@code math.random*}) names for one
 * projectile or vehicle. Unknown names return null (the expression sees 0).
 */
public final class EntityMolangContext extends AnimationContext {

    private static final MolangValue[] NO_ARGS = new MolangValue[0];

    private final EntityAnimator a;
    private final Random random = new Random();
    private final MolangValue[][] argStack = new MolangValue[PlayerMolangContext.MAX_FUNCTION_DEPTH + 1][];
    private int depth;

    EntityMolangContext(EntityAnimator animator, MolangVariables variables) {
        super(variables);
        this.a = animator;
    }

    @Override
    public MolangValue call(String namespace, String name, MolangValue[] args) {
        if (namespace == null || name == null || a.inputs == null) {
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

    @Override
    public MolangValue index(String namespace, String name, int index) {
        if (!"global".equals(namespace) || !"args".equals(name) || depth == 0) {
            return null;
        }
        MolangValue[] args = argStack[depth - 1];
        return index >= 0 && index < args.length ? args[index] : null;
    }

    private MolangValue userFunction(String name, MolangValue[] args) {
        MolangExpression body = a.model.functions.function(name);
        return body == null ? null : invoke(body, args.clone());
    }

    /** Runs a user function or event handler with its arguments ({@code args[i]}). */
    MolangValue invoke(MolangExpression body, MolangValue[] args) {
        if (depth >= PlayerMolangContext.MAX_FUNCTION_DEPTH) {
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
        EntitySnapshot in = a.inputs;
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
                return num(AnimMath.wrapDegrees(in.living ? in.bodyYaw : 0));
            case "body_x_rotation":
                return num(in.pitch);
            case "cardinal_facing_2d":
                return num(PlayerMolangContext.cardinalFacing(in.yaw));
            case "eye_target_y_rotation":
                return num(in.yaw);
            case "eye_target_x_rotation":
                return num(in.pitch);
            case "ground_speed":
                return num(20 * (float) Math.sqrt(in.motionX * in.motionX + in.motionZ * in.motionZ));
            case "head_x_rotation":
            case "head_y_rotation":
                return MolangValue.ZERO;
            case "health":
                return num(in.living ? in.health : 0);
            case "max_health":
                return num(in.living ? in.maxHealth : 0);
            case "is_in_water":
                return MolangValue.of(in.inWater);
            case "is_on_ground":
                return MolangValue.of(in.onGround);
            case "is_on_fire":
                return MolangValue.of(in.onFire);
            case "is_eating":
            case "is_jumping":
            case "is_sneaking":
            case "is_swimming":
                return MolangValue.ZERO;
            case "has_rider":
                return MolangValue.of(in.hasRider);
            case "time_stamp":
                return num(in.worldTime);
            case "time_of_day":
                return num(PlayerMolangContext.normalizeTime(in.worldTime));
            case "yaw_speed":
                return num(20 * in.yawSpeed);
            case "position":
                return position(args, false);
            case "position_delta":
                return position(args, true);
            case "vertical_speed":
                return num(a.renderTickDelta == 0 ? 0 : 20 * (float) a.positionDelta[1] / a.renderTickDelta);
            default:
                return null;
        }
    }

    private MolangValue ysm(String name, MolangValue[] args) {
        EntitySnapshot in = a.inputs;
        switch (name) {
            case "shoot_item_id":
                return MolangValue.of(in.shootItemId == null ? "" : in.shootItemId);
            case "is_open_air":
                return MolangValue.of(in.openAir);
            case "weather":
                return num(in.weather);
            case "fps":
                return num(in.fps);
            case "yya":
                return num(in.living ? in.moveForward : 0);
            case "ground_speed2": {
                double dx = a.positionDelta[0], dz = a.positionDelta[2];
                return num(a.renderTickDelta == 0 ? 0 : 20 * (float) Math.sqrt(dx * dx + dz * dz) / a.renderTickDelta);
            }
            case "head_pitch":
            case "head_yaw":
            case "elytra_rot_x":
            case "elytra_rot_y":
            case "elytra_rot_z":
            case "is_close_eyes":
            case "is_fishing":
            case "rendering_in_inventory":
            case "has_helmet":
            case "has_chest_plate":
            case "has_leggings":
            case "has_boots":
            case "has_mainhand":
            case "has_offhand":
            case "has_elytra":
            case "armor_value":
            case "mainhand_charged_crossbow":
            case "offhand_charged_crossbow":
            case "in_shield_block_cooldown":
                return MolangValue.ZERO;
            case "texture_name":
                return MolangValue.of("");
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
            case "defer":
                if (args.length >= 1 && args.length <= ModelFunctions.DEFER_MAX_ARGUMENTS) {
                    a.defer(floats(args));
                }
                return MolangValue.ZERO;
            // a projectile or vehicle is a consumer: nothing to send; no particle backend yet
            case "sync":
            case "particle":
            case "abs_particle":
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
            case "state_bypass":
                return num(5);
            case "state_continue":
                return num(2);
            case "state_stop":
                return num(3);
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
            // the player's states and item checks: false for a projectile or vehicle subject
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
            case "playing_extra_animation":
            case "armor":
            case "hold":
            case "swing":
            case "use":
                return MolangValue.ZERO;
            default:
                return null;
        }
    }

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

    private MolangValue position(MolangValue[] args, boolean delta) {
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
        EntitySnapshot in = a.inputs;
        double pt = in.partialTicks;
        double v = axis == 0 ? in.prevX + (in.x - in.prevX) * pt
            : axis == 1 ? in.prevY + (in.y - in.prevY) * pt : in.prevZ + (in.z - in.prevZ) * pt;
        return num((float) v);
    }

    private MolangValue boneRotation(MolangValue[] args) {
        if (args.length < 1) {
            return MolangValue.vector(0, 0, 0);
        }
        int bone = a.model.indexOf(str(args, 0));
        if (bone < 0) {
            return MolangValue.vector(0, 0, 0);
        }
        AnimationProcessor p = a.processor;
        float x = p.committedRotation(bone, 0) + p.initialRotation(bone, 0);
        float y = p.committedRotation(bone, 1) + p.initialRotation(bone, 1);
        float z = p.committedRotation(bone, 2) + p.initialRotation(bone, 2);
        return MolangValue.vector(-x * AnimMath.RAD_TO_DEG, -y * AnimMath.RAD_TO_DEG, z * AnimMath.RAD_TO_DEG);
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

    private static float[] floats(MolangValue[] args) {
        float[] values = new float[args.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = (float) args[i].asNumber();
        }
        return values;
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

/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/controller/collections/ProjectileControllerCollection.java,
 * src/main/java/com/elfmcys/ysm/client/controller/collections/VehicleControllerCollection.java, the predicates
 * src/main/java/com/elfmcys/ysm/client/animation/predicate/ProjectileMainPredicate.java, VehicleMainPredicate.java,
 * VehicleMovePredicate.java, VehicleRidePredicate.java and ParallelPredicate.java, the frame flow of
 * src/main/java/com/elfmcys/ysm/client/entity/AnimatableEntity.java (tickAnimationNow, advanceComponentTime) and the
 * consumer role of src/main/java/com/elfmcys/ysm/capability/ProjectileAnimatableCapability.java and
 * VehicleAnimatableCapability.java (roaming variables taken from the owner, never sent back), Apache License 2.0.
 * Changes: one object per entity and target, driven by an EntitySnapshot; no async ticker; the coded vehicle.origin
 * controller (rotations from Immersive Aircraft / Simple Planes) is not ported; roaming variables are copied from the
 * owner's animator on the client instead of arriving from the server.
 */
package com.akashic.futura.anim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.akashic.futura.anim.physics.PhysicsManager;
import com.akashic.futura.model.ReplacementTarget;
import com.akashic.futura.molang.MolangExpression;
import com.akashic.futura.molang.MolangValue;
import com.akashic.futura.molang.MolangVariables;

/**
 * Animates one projectile or vehicle with its replacement target. Call {@link #update} once per frame before drawing,
 * then draw with {@link #pose()}. Not thread-safe; keep it on the render thread.
 *
 * <pre>
 * projectile:  projectile.pre_main*  main (0.1 s)  post_main*  parallel_N (0 s, additive rotation)
 * vehicle:     vehicle.pre_parallel_N (0 s)  pre_main*  main (0.1 s)  move (0.1 s)  ride (0.1 s)  post_main*
 *              parallel_N (0 s, additive rotation)
 * </pre>
 *
 * Starred controllers, and {@code main}, {@code move} and {@code ride}, exist only when the target declares them, the
 * model overrides them or one of their animations is a non-empty clip (upstream {@code SingleControllerDiscovery}).
 */
public final class EntityAnimator {

    /** Upstream {@code ProjectileMainPredicate.ANIM_LIST} (it plays {@code air} in flight, which is not listed). */
    static final String[] PROJECTILE_MAIN = { "water", "ground", "fly", "fire" };
    static final String[] VEHICLE_MAIN = { "water", "ground", "fly" };
    static final String[] VEHICLE_MOVE = { "forward", "idle" };
    static final String[] VEHICLE_RIDE = { "has_ride", "not_ride" };
    /** Upstream {@code VehicleMovePredicate}: horizontal speed in blocks per tick above which it moves forward. */
    static final double MOVE_THRESHOLD = 0.05;

    final EntityAnimations model;
    final AnimationProcessor processor;
    private final List<AnimationController> controllers;
    private final EntityMolangContext context;
    final PhysicsManager physics = new PhysicsManager();
    private final BonePose pose;
    private final BoneAttributes attributes;
    private final ArrayDeque<float[]> deferred = new ArrayDeque<>();
    private int deferredThisFrame;

    EntitySnapshot inputs;

    float seekTime;
    private double lastFrameTime = -1;

    private boolean hasPosition;
    final double[] position = new double[3];
    final double[] positionDelta = new double[3];
    float renderTickDelta;
    private float positionSampleTime;
    private boolean sampled;
    private float sampledAnimationTime;

    public EntityAnimator(EntityAnimations model) {
        this.model = model;
        this.processor = new AnimationProcessor(model.initialRotations);
        this.pose = new BonePose(model.geometry.bones.length);
        this.attributes = new BoneAttributes(model.geometry);
        this.context = new EntityMolangContext(this, new MolangVariables());
        this.controllers = model.kind == ReplacementTarget.Kind.PROJECTILE ? projectileControllers()
            : vehicleControllers();
    }

    private List<AnimationController> projectileControllers() {
        List<AnimationController> list = new ArrayList<>();
        single(list, "pre_main", null, 0, EMPTY);
        single(list, "main", PROJECTILE_MAIN, 0.1f, this::projectileMain);
        single(list, "post_main", null, 0, EMPTY);
        parallel(list, model.parallel, true);
        return list;
    }

    private List<AnimationController> vehicleControllers() {
        List<AnimationController> list = new ArrayList<>();
        parallel(list, model.preParallel, false);
        single(list, "pre_main", null, 0, EMPTY);
        single(list, "main", VEHICLE_MAIN, 0.1f, this::vehicleMain);
        single(list, "move", VEHICLE_MOVE, 0.1f, this::vehicleMove);
        single(list, "ride", VEHICLE_RIDE, 0.1f, this::vehicleRide);
        single(list, "post_main", null, 0, EMPTY);
        parallel(list, model.parallel, true);
        return list;
    }

    /** {@code EmptyPredicate}: always stops. */
    private static final CodedController.Predicate EMPTY = c -> PlayState.STOP;

    private void single(List<AnimationController> list, String name, String[] animations, float transition,
        CodedController.Predicate predicate) {
        if (model.declares(name, animations)) {
            list.add(hybrid(model.category + "." + name, transition, false, predicate));
        }
    }

    private void parallel(List<AnimationController> list, Map<String, String> names, boolean blendRotation) {
        for (Map.Entry<String, String> e : names.entrySet()) {
            String animation = e.getValue();
            list.add(hybrid(e.getKey(), 0, blendRotation, animation != null ? c -> loop(c, animation) : EMPTY));
        }
    }

    private AnimationController hybrid(String name, float transition, boolean blendRotation,
        CodedController.Predicate predicate) {
        CodedController coded = new CodedController(
            name,
            model.library,
            processor.committed,
            model,
            transition,
            blendRotation,
            predicate);
        coded.setOverride(model.functions.first(ModelFunctions.overrideEvent(name)));
        ControllerData data = model.controllers.get(name);
        BedrockController bedrock = data == null ? null
            : new BedrockController(
                name,
                model.library,
                processor.committed,
                model,
                transition,
                model.controllers::get,
                data);
        return new HybridController(coded, bedrock);
    }

    private static PlayState loop(CodedController c, String animation) {
        c.setAnimation(animation, com.akashic.futura.format.pojo.animation.LoopType.LOOP);
        return PlayState.CONTINUE;
    }

    /** {@code ProjectileMainPredicate}: water, fire, stuck in a block, else {@code air}. */
    private PlayState projectileMain(CodedController c) {
        EntitySnapshot in = inputs;
        if (in.inWater) {
            c.setAnimation("water");
        } else if (in.onFire) {
            c.setAnimation("fire");
        } else if (in.inGround) {
            c.setAnimation("ground");
        } else {
            c.setAnimation("air");
        }
        return PlayState.CONTINUE;
    }

    /** {@code VehicleMainPredicate}. */
    private PlayState vehicleMain(CodedController c) {
        EntitySnapshot in = inputs;
        c.setAnimation(in.inWater ? "water" : in.onGround ? "ground" : "fly");
        return PlayState.CONTINUE;
    }

    /** {@code VehicleMovePredicate}: {@code forward} above 0.05 blocks per tick horizontally, else {@code idle}. */
    private PlayState vehicleMove(CodedController c) {
        EntitySnapshot in = inputs;
        double speed = Math.sqrt(in.motionX * in.motionX + in.motionZ * in.motionZ);
        c.setAnimation(speed > MOVE_THRESHOLD ? "forward" : "idle");
        return PlayState.CONTINUE;
    }

    /** {@code VehicleRidePredicate}. */
    private PlayState vehicleRide(CodedController c) {
        c.setAnimation(inputs.hasRider ? "has_ride" : "not_ride");
        return PlayState.CONTINUE;
    }

    /**
     * Advances the animation to {@code renderTicks} and recomputes the pose; a frame with the same time samples again,
     * a time going backwards re-bases the clock.
     *
     * @param renderTicks {@code ticksExisted + partialTicks}
     */
    public void update(EntitySnapshot inputs, double renderTicks) {
        this.inputs = inputs;
        boolean tick = false;
        if (renderTicks > lastFrameTime) {
            if (lastFrameTime >= 0) {
                seekTime += (float) (renderTicks - lastFrameTime);
            }
            lastFrameTime = renderTicks;
            tick = true;
        } else if (renderTicks < lastFrameTime) {
            lastFrameTime = renderTicks;
        }
        if (tick) {
            deferredThisFrame = 0;
            advanceComponentTime();
            physics.update(seekTime);
            processor.tick(controllers, seekTime, context, true);
            drainDeferred();
            context.globalSounds.trim();
        }
        writePose();
    }

    private void advanceComponentTime() {
        float currentTime = seekTime;
        boolean first = !sampled;
        float previousTime = first ? currentTime : sampledAnimationTime;
        sampled = true;
        sampledAnimationTime = currentTime;
        if ((first || currentTime > previousTime) && !(hasPosition && previousTime == currentTime)) {
            EntitySnapshot in = inputs;
            double pt = in.partialTicks;
            double x = in.prevX + (in.x - in.prevX) * pt;
            double y = in.prevY + (in.y - in.prevY) * pt;
            double z = in.prevZ + (in.z - in.prevZ) * pt;
            renderTickDelta = hasPosition ? Math.max(0, currentTime - positionSampleTime) : 0;
            if (!hasPosition || renderTickDelta == 0) {
                positionDelta[0] = positionDelta[1] = positionDelta[2] = 0;
            } else {
                positionDelta[0] = x - position[0];
                positionDelta[1] = y - position[1];
                positionDelta[2] = z - position[2];
            }
            position[0] = x;
            position[1] = y;
            position[2] = z;
            hasPosition = true;
            positionSampleTime = currentTime;
        }
    }

    /** {@code ysm.defer(delay, values...)}: queued, run newest first at the end of the frame. */
    void defer(float[] values) {
        for (float v : values) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return;
            }
        }
        if (deferredThisFrame >= PlayerAnimator.MAX_DEFERRED) {
            return;
        }
        deferredThisFrame++;
        deferred.addLast(values);
    }

    private void drainDeferred() {
        List<MolangExpression> handlers = model.functions.handlers(ModelFunctions.DEFER);
        while (!deferred.isEmpty()) {
            float[] values = deferred.pollLast();
            MolangValue[] args = new MolangValue[values.length];
            for (int i = 0; i < values.length; i++) {
                args[i] = MolangValue.of((double) values[i]);
            }
            for (int i = 0; i < handlers.size(); i++) {
                context.invoke(handlers.get(i), args);
            }
        }
    }

    private void writePose() {
        float[] p = pose.values;
        float[] c = processor.committed;
        for (int o = 0; o < p.length; o += BonePose.FLOATS) {
            p[o] = c[o];
            p[o + 1] = c[o + 1];
            p[o + 2] = c[o + 2];
            p[o + 3] = -c[o + 3] / 16f;
            p[o + 4] = c[o + 4] / 16f;
            p[o + 5] = c[o + 5] / 16f;
            p[o + 6] = c[o + 6];
            p[o + 7] = c[o + 7];
            p[o + 8] = c[o + 8];
        }
    }

    /**
     * Takes the owner's roaming variables (upstream's consumer role: the projectile gets its shooter's at the shot, the
     * vehicle its rider's while ridden): every numeric {@code v.roaming.*} of {@code owner} is written here; writes
     * made here never go back.
     */
    public void takeRoaming(MolangVariables owner) {
        if (owner == null) {
            return;
        }
        Map<String, Float> values = new HashMap<>();
        owner.roamingNumbers(values);
        MolangVariables mine = context.variables();
        for (Map.Entry<String, Float> e : values.entrySet()) {
            mine.set("roaming." + e.getKey(), MolangValue.of((double) e.getValue()));
        }
    }

    /** Where this entity's sounds play ({@link SoundBackend#NONE} by default). */
    public void setSoundBackend(SoundBackend backend) {
        context.setSoundBackend(backend);
    }

    /** Stops every sound this animator started (the entity is gone, or its model changed). */
    public void stopSounds() {
        context.globalSounds.stopAll();
        for (AnimationController c : controllers) {
            SoundScope scope = c.soundScope();
            if (scope != null) {
                scope.stopAll();
            }
            if (c instanceof HybridController) {
                HybridController h = (HybridController) c;
                h.coded()
                    .player()
                    .stopSounds();
                if (h.bedrock() != null) {
                    h.bedrock()
                        .stopAllSounds();
                }
            }
        }
    }

    /** The pose for the last {@link #update}. Reused: do not keep it across frames. */
    public BonePose pose() {
        return pose;
    }

    /** Colour, alpha and glow written by Molang ({@code ysm.bone_*}). */
    public BoneAttributes attributes() {
        return attributes;
    }

    /** The entity's {@code v.*} variables. */
    public MolangVariables variables() {
        return context.variables();
    }

    public EntityAnimations model() {
        return model;
    }

    /** The controller stack, in processing order. */
    public List<AnimationController> controllers() {
        return controllers;
    }

    /** The clip a coded controller plays now ({@code projectile.main}...), or null. */
    public String playing(String controller) {
        for (AnimationController c : controllers) {
            if (!c.name()
                .equals(controller)) {
                continue;
            }
            if (c instanceof HybridController) {
                c = ((HybridController) c).active();
            }
            if (c instanceof CodedController) {
                AnimationPlayer p = ((CodedController) c).player();
                AnimationClip clip = p.state() == PlaybackState.IDLE ? null : p.current();
                return clip == null ? null : clip.name;
            }
        }
        return null;
    }

    /** Animation time in seconds since the animator started ({@code query.life_time}). */
    public float lifeTime() {
        return seekTime / 20f;
    }
}

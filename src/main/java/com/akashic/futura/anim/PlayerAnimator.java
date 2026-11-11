/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the frame flow of src/main/java/com/elfmcys/ysm/client/entity/AnimatableEntity.java (createAnimationEvent,
 * tickAnimationNow, advanceComponentTime), src/main/java/com/elfmcys/ysm/client/entity/CustomHumanoidEntity.java
 * (codeAnimation: the head look), src/main/java/com/elfmcys/ysm/client/entity/CustomPlayerEntity.java (extra
 * animations), src/main/java/com/elfmcys/ysm/geckolib3/model/EntityStateTracker.java and
 * src/main/java/com/elfmcys/ysm/expression/host/builtin/PositionComponent.java and
 * src/main/java/com/elfmcys/ysm/client/entity/CustomFirstPersonArmEntity.java (the first-person arms: own controllers,
 * roaming variables projected from the body), Apache License 2.0.
 * src/main/java/com/elfmcys/ysm/expression/aot/EntityExpressionRuntime.java (event dispatch: player_init,
 * player_update,
 * sync, deferred captures), Apache License 2.0.
 * Changes: one object per player and model, driven by PlayerInputs; no async ticker, rate limiter, GUI previews or
 * first-person head hiding; sync goes through a SyncSink seam; deferred captures run at the end of the frame's
 * events and of the frame, newest first, without restoring the animation context they were made in.
 */
package com.akashic.futura.anim;

import java.util.ArrayDeque;
import java.util.List;

import com.akashic.futura.anim.physics.PhysicsManager;
import com.akashic.futura.molang.MolangExpression;
import com.akashic.futura.molang.MolangValue;
import com.akashic.futura.molang.MolangVariables;

/**
 * Animates one player with one model. Call {@link #update} once per frame before drawing, then draw with
 * {@link #pose()}. Not thread-safe; keep it on the render thread.
 *
 * <pre>
 * ModelAnimations anims = ModelAnimations.compile(loadedModel); // once per model
 * PlayerAnimator animator = new PlayerAnimator(anims); // once per player
 * animator.update(inputs, player.ticksExisted + partialTicks); // every frame
 * geometryRenderer.draw(animator.pose());
 * </pre>
 *
 * The first-person arms are a second animator of the same player: {@link #firstPerson} with the model's
 * {@link ModelAnimations#compileFirstPerson arm animations}.
 */
public final class PlayerAnimator {

    static final int TICK_SWING = 1;
    static final int TICK_USE = 2;
    /** Upstream {@code MAX_DEFERRED_CAPTURES}: {@code ysm.defer} calls one frame may queue. */
    static final int MAX_DEFERRED = 64;

    final ModelAnimations model;
    private final AnimationProcessor processor;
    private final List<AnimationController> controllers;
    private final AnimationController cap;
    private final PlayerMolangContext context;
    final PhysicsManager physics = new PhysicsManager();
    private final BonePose pose;
    private final BoneAttributes attributes;

    PlayerInputs inputs;

    // events (upstream EntityExpressionRuntime)
    private boolean initFired;
    private final ArrayDeque<float[]> deferred = new ArrayDeque<>();
    private int deferredThisFrame;
    private SyncSink syncSink;

    // frame timing (upstream seekTime / lastFrameTime)
    float seekTime;
    private double lastFrameTime = -1;

    // per-frame values (upstream EntityModelData)
    float limbSwing;
    float limbSwingAmount;
    float headPitch;
    float netHeadYaw;
    float rawHeadPitch;
    float rawNetHeadYaw;
    float lerpBodyRot;
    boolean sitting;

    // entity-tick state (upstream EntityStateTracker)
    private boolean sampled;
    private int sampledEntityTick;
    private float sampledAnimationTime;
    private int entityTickStates;
    ItemInfo heldMainhand = ItemInfo.EMPTY;
    ItemInfo heldOffhand = ItemInfo.EMPTY;
    String selectedControl;

    // SlashBlade combo (SlashBladeStates): the generation of the combo state last started
    int comboGeneration;

    // the rod (FishingStates): its phase, and the serial of the phase the fishing controller last started
    final FishingStates.Tracker fishing = new FishingStates.Tracker();
    int fishingSerial = -1;
    /** Keeps the rod straight ahead of the torso while fishing (created on first use). */
    private RodAim rodAim;

    // extra animation (upstream CustomPlayerEntity)
    boolean playingExtra;
    boolean extraDirty;
    String extraName = MainStates.IDLE;

    // position history (upstream PositionComponent)
    private boolean hasPosition;
    final double[] position = new double[3];
    final double[] positionDelta = new double[3];
    float renderTickDelta;
    private float positionSampleTime;

    public PlayerAnimator(ModelAnimations model) {
        this(model, new MolangVariables());
    }

    private PlayerAnimator(ModelAnimations model, MolangVariables variables) {
        this.model = model;
        this.processor = new AnimationProcessor(model.initialRotations);
        this.pose = new BonePose(model.geometry.bones.length);
        this.attributes = new BoneAttributes(model.geometry);
        this.context = new PlayerMolangContext(this, variables);
        this.controllers = model.firstPerson ? FirstPersonControllers.build(this, model, processor.committed)
            : PlayerControllers.build(this, model, processor.committed);
        AnimationController found = null;
        for (AnimationController controller : controllers) {
            if (PlayerControllers.CAP.equals(controller.name())) {
                found = controller;
            }
        }
        this.cap = found;
    }

    /**
     * The first-person arms of a player (upstream {@code CustomFirstPersonArmEntity}): {@code fp.arm.*} controllers on
     * the {@code arm} geometry. Its {@code v.roaming.*} variables are the body's; its other variables are its own.
     *
     * @param arms the model's {@link ModelAnimations#compileFirstPerson first-person animations}
     * @param body the same player's body animator
     */
    public static PlayerAnimator firstPerson(ModelAnimations arms, PlayerAnimator body) {
        if (!arms.firstPerson) {
            throw new IllegalArgumentException("not first-person animations");
        }
        return new PlayerAnimator(arms, new MolangVariables(body.variables()));
    }

    /**
     * Advances the animation to {@code renderTicks} and recomputes the pose. A frame with the same time as the last
     * one only refreshes the head look; a time going backwards (a new entity) re-bases the clock without advancing.
     *
     * @param renderTicks {@code ticksExisted + partialTicks}
     */
    public void update(PlayerInputs inputs, double renderTicks) {
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
        computeFrame();
        if (tick) {
            deferredThisFrame = 0;
            advanceComponentTime();
            physics.update(seekTime);
            drainDeferred();
            dispatchPersistentEvents();
            processor.tick(controllers, seekTime, context, true);
            if (playingExtra && cap != null && cap.state() == PlaybackState.IDLE) {
                playingExtra = false;
            }
            drainDeferred();
            trimSounds();
        }
        writePose();
        straightenRod();
    }

    /** While fishing, the rod straight ahead of the torso whatever the model's arms do ({@link RodAim}). */
    private void straightenRod() {
        if (model.firstPerson || inputs == null || !fishing.phase().straight || !FishingStates.holdsRod(inputs)) {
            return;
        }
        if (rodAim == null) {
            rodAim = new RodAim(model.geometry);
        }
        rodAim.straighten(pose);
    }

    /** Upstream {@code dispatchPersistentEvents}: {@code player_init} once, then {@code player_update(true)}. */
    private void dispatchPersistentEvents() {
        if (!initFired) {
            initFired = true;
            dispatch(ModelFunctions.PLAYER_INIT, new MolangValue[0]);
        }
        dispatch(ModelFunctions.PLAYER_UPDATE, new MolangValue[] { MolangValue.ONE });
    }

    private void dispatch(String event, MolangValue[] args) {
        List<MolangExpression> handlers = model.functions.handlers(event);
        for (int i = 0; i < handlers.size(); i++) {
            context.invoke(handlers.get(i), args);
        }
    }

    private static MolangValue[] values(float[] values) {
        MolangValue[] args = new MolangValue[values.length];
        for (int i = 0; i < values.length; i++) {
            args[i] = MolangValue.of((double) values[i]);
        }
        return args;
    }

    /** {@code ysm.sync(values)}: to the network through the {@link SyncSink}, or to the {@code @sync} handlers now. */
    void sync(float[] values) {
        for (float v : values) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return;
            }
        }
        SyncSink sink = syncSink;
        boolean taken = false;
        if (sink != null) {
            try {
                taken = sink.emit(values.clone());
            } catch (RuntimeException e) {
                taken = false;
            }
        }
        if (!taken) {
            dispatchSync(values);
        }
    }

    /**
     * Runs the model's {@code @sync} handlers with {@code args[0..]} = the values (the network layer calls this with
     * what a player's {@code ysm.sync} sent; at most 16 values, finite).
     */
    public void dispatchSync(float[] values) {
        if (values == null || values.length > ModelFunctions.SYNC_MAX_ARGUMENTS || inputs == null) {
            return;
        }
        for (float v : values) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return;
            }
        }
        dispatch(ModelFunctions.SYNC, values(values));
    }

    /** Where this player's {@code ysm.sync} goes; null dispatches locally. */
    public void setSyncSink(SyncSink sink) {
        this.syncSink = sink;
    }

    /** Where this player's sounds play ({@link SoundBackend#NONE} by default). */
    public void setSoundBackend(SoundBackend backend) {
        context.setSoundBackend(backend);
    }

    /** {@code ysm.defer(delay, values...)}: queued, run newest first with {@code args} = delay then values. */
    void defer(float[] values) {
        for (float v : values) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return;
            }
        }
        if (deferredThisFrame >= MAX_DEFERRED) {
            return;
        }
        deferredThisFrame++;
        deferred.addLast(values);
    }

    private void drainDeferred() {
        List<MolangExpression> handlers = model.functions.handlers(ModelFunctions.DEFER);
        while (!deferred.isEmpty()) {
            float[] values = deferred.pollLast();
            if (!handlers.isEmpty()) {
                dispatch(ModelFunctions.DEFER, values(values));
            }
        }
    }

    /** Stops every sound this animator started (the player left, or the model changed). */
    public void stopSounds() {
        context.globalSounds.stopAll();
        for (AnimationController c : controllers) {
            stopSounds(c);
        }
    }

    private static void stopSounds(AnimationController c) {
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
        } else if (c instanceof CodedController) {
            ((CodedController) c).player()
                .stopSounds();
        }
    }

    private void trimSounds() {
        context.globalSounds.trim();
    }

    /** The pose of the main geometry for the last {@link #update}. Reused: do not keep it across frames. */
    public BonePose pose() {
        return pose;
    }

    /** Colour, alpha and glow written by Molang ({@code ysm.bone_*}). */
    public BoneAttributes attributes() {
        return attributes;
    }

    /**
     * Plays an extra (emote) animation on the {@code cap} controller, e.g. {@code extra0}.
     *
     * @return false if neither the model nor the default model has it
     */
    public boolean playExtra(String name) {
        if (name != null && model.library.get(name) != null) {
            extraName = name;
            playingExtra = true;
            extraDirty = true;
            return true;
        }
        playingExtra = false;
        return false;
    }

    /** Stops the extra animation (it blends out). */
    public void stopExtra() {
        playingExtra = false;
    }

    /** true while an extra animation plays (it ends by itself when a play-once clip finishes). */
    public boolean isPlayingExtra() {
        return playingExtra;
    }

    /**
     * The clip a coded controller plays now ({@code player.main}, {@code player.swing}...), or null when it plays
     * nothing (idle, or not a coded controller).
     */
    public String playing(String controller) {
        for (AnimationController c : controllers) {
            if (!c.name()
                .equals(controller)) {
                continue;
            }
            // Coded controllers sit inside a HybridController (phase 5a): only the coded side has a clip to report.
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

    /** The player's {@code v.*} variables. */
    public MolangVariables variables() {
        return context.variables();
    }

    /** Body yaw the model should be drawn with (clamped against a living vehicle as upstream does). */
    public float renderBodyYaw() {
        return lerpBodyRot;
    }

    public ModelAnimations model() {
        return model;
    }

    public PlayerMolangContext context() {
        return context;
    }

    AnimationProcessor processor() {
        return processor;
    }

    /** The controller stack, in processing order. */
    public List<AnimationController> controllers() {
        return controllers;
    }

    /** The controller of that name, or null. */
    public AnimationController controller(String name) {
        for (AnimationController c : controllers) {
            if (c.name()
                .equals(name)) {
                return c;
            }
        }
        return null;
    }

    PlaybackState capState() {
        return cap == null ? PlaybackState.IDLE : cap.state();
    }

    /** Animation time in seconds since the animator started ({@code query.life_time}). */
    public float lifeTime() {
        return seekTime / 20f;
    }

    /** Upstream {@code createAnimationEvent}: limb swing and head angles of this frame. */
    private void computeFrame() {
        PlayerInputs in = inputs;
        sitting = in.isRiding() && in.riderSits();
        limbSwing = 0;
        limbSwingAmount = 0;
        if (!sitting && !in.isDeadOrDying()) {
            limbSwingAmount = in.limbSwingAmount();
            limbSwing = in.limbSwing();
        }
        float body = in.bodyYaw();
        float head = in.headYaw();
        float net = head - body;
        if (sitting && in.vehicleIsLiving()) {
            body = in.vehicleBodyYaw();
            net = head - body;
            float clamped = AnimMath.clamp(AnimMath.wrapDegrees(net), -85, 85);
            body = head - clamped;
            if (clamped * clamped > 2500f) {
                body += clamped * 0.2f;
            }
            net = head - body;
        }
        rawHeadPitch = in.pitch();
        headPitch = -rawHeadPitch;
        rawNetHeadYaw = net;
        netHeadYaw = -AnimMath.clamp(AnimMath.wrapDegrees(net), -85, 85);
        lerpBodyRot = body;
    }

    /** Upstream {@code advanceComponentTime}: entity-tick states, the {@code ctrl} cache and the position history. */
    private void advanceComponentTime() {
        int currentTick = inputs.ticksExisted();
        float currentTime = seekTime;
        boolean first = !sampled;
        float previousTime = first ? currentTime : sampledAnimationTime;
        if (first || currentTick != sampledEntityTick) {
            entityTickStates = 0;
        }
        sampled = true;
        sampledEntityTick = currentTick;
        sampledAnimationTime = currentTime;
        if (first || currentTime > previousTime) {
            selectedControl = null;
            updatePosition(previousTime, currentTime);
        }
    }

    private void updatePosition(float previousTime, float currentTime) {
        if (hasPosition && previousTime == currentTime) {
            return;
        }
        double pt = inputs.partialTicks();
        double x = inputs.prevX() + (inputs.x() - inputs.prevX()) * pt;
        double y = inputs.prevY() + (inputs.y() - inputs.prevY()) * pt;
        double z = inputs.prevZ() + (inputs.z() - inputs.prevZ()) * pt;
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

    /** Marks a once-per-entity-tick event; false if already marked this tick. */
    boolean setEntityTickState(int flag) {
        if ((entityTickStates & flag) != 0) {
            return false;
        }
        entityTickStates |= flag;
        return true;
    }

    /** The rod's phase at the last frame ({@link FishingStates}), for logs, other mods and the harness. */
    public FishingStates.Phase fishingPhase() {
        return fishing.phase();
    }

    /** The main state of the last frame ({@code walk}, {@code jump}, {@code idle}...), for logs and the harness. */
    public String mainState() {
        return selectedControl();
    }

    /** The {@code ctrl.*} state, cached for the frame (upstream {@code selectedControl}). */
    String selectedControl() {
        if (selectedControl == null) {
            selectedControl = inputs.isRiding() ? "" : MainStates.select(inputs, limbSwingAmount, true);
        }
        return selectedControl;
    }

    private void writePose() {
        float[] p = pose.values;
        float[] c = processor.committed;
        for (int o = 0; o < p.length; o += BonePose.FLOATS) {
            p[o] = c[o];
            p[o + 1] = c[o + 1];
            p[o + 2] = c[o + 2];
            // pixels on Bedrock axes to blocks in model space (x negated)
            p[o + 3] = -c[o + 3] / 16f;
            p[o + 4] = c[o + 4] / 16f;
            p[o + 5] = c[o + 5] / 16f;
            p[o + 6] = c[o + 6];
            p[o + 7] = c[o + 7];
            p[o + 8] = c[o + 8];
        }
        // the head follows the look by code, on top of the animation (2.x CustomHumanoidEntity.codeAnimation)
        float pitch = headPitch * AnimMath.DEG_TO_RAD;
        float yaw = netHeadYaw * AnimMath.DEG_TO_RAD;
        for (int bone : model.headBones) {
            int o = bone * BonePose.FLOATS;
            p[o] += pitch;
            p[o + 1] += yaw;
        }
    }
}

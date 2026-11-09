/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the frame flow of src/main/java/com/elfmcys/ysm/client/entity/AnimatableEntity.java (createAnimationEvent,
 * tickAnimationNow, advanceComponentTime), src/main/java/com/elfmcys/ysm/client/entity/CustomHumanoidEntity.java
 * (codeAnimation: the head look), src/main/java/com/elfmcys/ysm/client/entity/CustomPlayerEntity.java (extra
 * animations), src/main/java/com/elfmcys/ysm/geckolib3/model/EntityStateTracker.java and
 * src/main/java/com/elfmcys/ysm/expression/host/builtin/PositionComponent.java, Apache License 2.0.
 * Changes: one object per player and model, driven by PlayerInputs; no async ticker, rate limiter, GUI previews,
 * first-person head hiding or network sync.
 */
package com.akashic.futura.anim;

import java.util.List;

import com.akashic.futura.anim.physics.PhysicsManager;
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
 */
public final class PlayerAnimator {

    static final int TICK_SWING = 1;
    static final int TICK_USE = 2;

    final ModelAnimations model;
    private final AnimationProcessor processor;
    private final List<AnimationController> controllers;
    private final AnimationController cap;
    private final PlayerMolangContext context;
    final PhysicsManager physics = new PhysicsManager();
    private final BonePose pose;
    private final BoneAttributes attributes;

    PlayerInputs inputs;

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
        this.model = model;
        this.processor = new AnimationProcessor(model.initialRotations);
        this.pose = new BonePose(model.geometry.bones.length);
        this.attributes = new BoneAttributes(model.geometry);
        this.context = new PlayerMolangContext(this);
        this.controllers = PlayerControllers.build(this, model, processor.committed);
        AnimationController found = null;
        for (AnimationController controller : controllers) {
            if (PlayerControllers.CAP.equals(controller.name())) {
                found = controller;
            }
        }
        this.cap = found;
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
            advanceComponentTime();
            physics.update(seekTime);
            processor.tick(controllers, seekTime, context, true);
            if (playingExtra && cap != null && cap.state() == PlaybackState.IDLE) {
                playingExtra = false;
            }
        }
        writePose();
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

    List<AnimationController> controllers() {
        return controllers;
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

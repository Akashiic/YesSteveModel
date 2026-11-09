/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/processor/AnimationProcessor.java (tickAnimation),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/CodedAnimationController.java (SingleBoneAnimationQueue),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/keyframe/AnimationVec3.java and
 * src/main/java/com/elfmcys/ysm/geckolib3/core/snapshot/BoneTopLevelSnapshot.java, Apache License 2.0.
 * Changes: bone snapshots are flat arrays; points are sampled in place without allocation; the transient Molang
 * tasks, sounds and debug hooks are not ported.
 */
package com.akashic.futura.anim;

import java.util.List;

/**
 * Blends the controllers' output into one pose per bone. Each frame, controllers run in order; for every bone and
 * channel a controller's value replaces what earlier ones wrote (fading against it while the controller blends out),
 * except while a {@code blendRotation} controller runs, which adds its rotation to the accumulated value. A channel no
 * controller wrote this frame eases back to rest over {@link AnimationPlayer#ENDING_TRANSITION_TICKS} ticks.
 * <p>
 * Values per bone: rotation in radians relative to the bone's rest rotation (x and y negated from the file),
 * position in pixels (Bedrock axes), scale.
 */
public final class AnimationProcessor {

    private final int n;
    /** rest rotation of every bone, 3 floats each */
    private final float[] initRot;

    // BoneTopLevelSnapshot fields, flat
    private final float[] rot;
    private final float[] pos;
    private final float[] scl;
    private final float[] cachedRot;
    private final boolean[] hasAnimation;
    private final boolean[] runRot;
    private final boolean[] runPos;
    private final boolean[] runScl;
    private final float[] lastRotTime;
    private final float[] lastPosTime;
    private final float[] lastSclTime;
    private final float[] rotOff;
    private final float[] posOff;
    private final float[] sclOff;
    private final boolean[] hasRotOff;
    private final boolean[] hasPosOff;
    private final boolean[] hasSclOff;
    /** Active bones, in the order they became active. */
    private final int[] active;
    private int activeCount;

    /** The bones' committed pose, 9 floats each: rotation delta, position (pixels), scale. */
    final float[] committed;

    private final float resetTicks = AnimationPlayer.ENDING_TRANSITION_TICKS;
    private final float[] tmp = new float[3];
    private final float[] scratch = new float[12];
    private final float[] rotScratch = new float[8];
    private float renderTicks;
    private boolean blend;

    public AnimationProcessor(float[] initialRotations) {
        this.initRot = initialRotations.clone();
        this.n = initialRotations.length / 3;
        rot = new float[n * 3];
        pos = new float[n * 3];
        scl = new float[n * 3];
        cachedRot = new float[n * 3];
        hasAnimation = new boolean[n];
        runRot = new boolean[n];
        runPos = new boolean[n];
        runScl = new boolean[n];
        lastRotTime = new float[n];
        lastPosTime = new float[n];
        lastSclTime = new float[n];
        rotOff = new float[n * 3];
        posOff = new float[n * 3];
        sclOff = new float[n * 3];
        hasRotOff = new boolean[n];
        hasPosOff = new boolean[n];
        hasSclOff = new boolean[n];
        active = new int[n];
        committed = new float[n * 9];
        for (int b = 0; b < n; b++) {
            scl[b * 3] = scl[b * 3 + 1] = scl[b * 3 + 2] = 1;
            committed[b * 9 + 6] = committed[b * 9 + 7] = committed[b * 9 + 8] = 1;
            runRot[b] = runPos[b] = runScl[b] = true;
        }
    }

    public int boneCount() {
        return n;
    }

    /**
     * One frame.
     *
     * @param shouldTick whether controllers advance (otherwise their current points are sampled again)
     */
    public void tick(List<? extends AnimationController> controllers, float renderTicks, AnimationContext ctx,
        boolean shouldTick) {
        this.renderTicks = renderTicks;
        for (int i = 0; i < controllers.size(); i++) {
            AnimationController controller = controllers.get(i);
            if (shouldTick) {
                controller.process(renderTicks, ctx, shouldTick);
            }
            blend = controller.blendRotation();
            AnimationController saved = ctx.controller;
            ctx.controller = controller;
            try {
                controller.visit(this, ctx);
            } finally {
                ctx.controller = saved;
            }
        }
        settle();
    }

    /**
     * Samples one player's points into the snapshots. Called by controllers from {@link AnimationController#visit}.
     * While sampling, {@code query.anim_time} is the player's animation time.
     */
    public void poll(AnimationPlayer player, AnimationContext ctx) {
        List<BoneQueue> queues = player.activeQueues();
        if (queues.isEmpty()) {
            return;
        }
        float savedTime = ctx.animTime;
        ctx.animTime = player.animTime();
        try {
            for (int i = 0; i < queues.size(); i++) {
                pollQueue(queues.get(i), ctx);
            }
        } finally {
            ctx.animTime = savedTime;
        }
    }

    private void pollQueue(BoneQueue q, AnimationContext ctx) {
        int b = q.bone;
        if (!hasAnimation[b]) {
            hasAnimation[b] = true;
            active[activeCount++] = b;
        }
        float[] v = tmp;
        float w = q.blendWeight();
        int o = b * 3;

        if (q.kind[0] != BoneQueue.NONE) {
            float ending = 0;
            switch (q.kind[0]) {
                case BoneQueue.KEY:
                    q.segment[0].sample(ctx, q.progress[0], v, 0, scratch);
                    rememberLerp(q, 0, v);
                    if (w != 1) {
                        mul(v, w);
                    }
                    break;
                case BoneQueue.BEGIN:
                    q.channels[0].transitionTarget()
                        .eval(ctx, v, 0);
                    mul(v, w);
                    AnimMath.lerpRotation(q.progress[0], q.transitionOffset, 0, v, 0, initRot, o, v, 0, rotScratch);
                    break;
                default:
                    System.arraycopy(q.endOffset, 0, v, 0, 3);
                    rememberLerp(q, 0, v);
                    ending = q.progress[0];
                    if (w != 1) {
                        mul(v, w);
                    }
                    break;
            }
            if (!runRot[b]) {
                runRot[b] = true;
                rot[o] = rot[o + 1] = rot[o + 2] = 0;
            }
            lastRotTime[b] = renderTicks;
            if (blend) {
                // legacy additive parallel layers: assumed never to be blending out
                cachedRot[o] += v[0];
                cachedRot[o + 1] += v[1];
                cachedRot[o + 2] += v[2];
                System.arraycopy(cachedRot, o, rot, o, 3);
            } else {
                if (ending == 0) {
                    System.arraycopy(v, 0, rot, o, 3);
                } else {
                    AnimMath.lerpRotation(ending, v, 0, rot, o, initRot, o, rot, o, rotScratch);
                }
                System.arraycopy(rot, o, cachedRot, o, 3);
            }
        }

        if (q.kind[1] != BoneQueue.NONE) {
            float ending = pollLinear(q, 1, ctx, v);
            float weight = w;
            if (q.kind[1] == BoneQueue.BEGIN) {
                weight = AnimMath.lerp(q.progress[1], 1, w);
            }
            if (weight != 1) {
                mul(v, weight);
            }
            if (!runPos[b]) {
                runPos[b] = true;
                pos[o] = pos[o + 1] = pos[o + 2] = 0;
            }
            lastPosTime[b] = renderTicks;
            applyLinear(v, ending, pos, o);
        }

        if (q.kind[2] != BoneQueue.NONE) {
            float ending = pollLinear(q, 2, ctx, v);
            float weight = w;
            if (q.kind[2] == BoneQueue.BEGIN) {
                weight = AnimMath.lerp(q.progress[2], 1, w);
            }
            if (weight != 1) {
                for (int i = 0; i < 3; i++) {
                    v[i] = AnimMath.weightedScale(v[i], weight);
                }
            }
            if (!runScl[b]) {
                runScl[b] = true;
                scl[o] = scl[o + 1] = scl[o + 2] = 1;
            }
            lastSclTime[b] = renderTicks;
            applyLinear(v, ending, scl, o);
        }
    }

    /** Position or scale point value (unweighted); returns the ending-transition progress (0 if not ending). */
    private float pollLinear(BoneQueue q, int c, AnimationContext ctx, float[] v) {
        switch (q.kind[c]) {
            case BoneQueue.KEY:
                q.segment[c].sample(ctx, q.progress[c], v, 0, scratch);
                rememberLerp(q, c, v);
                return 0;
            case BoneQueue.BEGIN: {
                q.channels[c].transitionTarget()
                    .eval(ctx, v, 0);
                float t = q.progress[c];
                int off = c * 3;
                for (int i = 0; i < 3; i++) {
                    v[i] = AnimMath.lerp(t, q.transitionOffset[off + i], v[i]);
                }
                rememberLerp(q, c, v);
                return 0;
            }
            default:
                System.arraycopy(q.endOffset, c * 3, v, 0, 3);
                rememberLerp(q, c, v);
                return q.progress[c];
        }
    }

    private static void rememberLerp(BoneQueue q, int c, float[] v) {
        System.arraycopy(v, 0, q.lastLerp, c * 3, 3);
        q.hasLastLerp[c] = true;
    }

    private static void mul(float[] v, float w) {
        v[0] *= w;
        v[1] *= w;
        v[2] *= w;
    }

    /** {@code AnimationVec3.apply}: set, or fade from the controller's value to what was there. */
    private static void applyLinear(float[] v, float ending, float[] dst, int o) {
        if (ending == 0) {
            System.arraycopy(v, 0, dst, o, 3);
        } else {
            for (int i = 0; i < 3; i++) {
                dst[o + i] = AnimMath.lerp(ending, v[i], dst[o + i]);
            }
        }
    }

    /** Eases channels nobody wrote this frame back to rest, commits, and drops bones that are fully at rest. */
    private void settle() {
        int kept = 0;
        for (int i = 0; i < activeCount; i++) {
            int b = active[i];
            int o = b * 3;
            boolean alive = false;

            if (runRot[b]) {
                alive = true;
                runRot[b] = false;
                hasRotOff[b] = false;
            } else {
                if (!hasRotOff[b]) {
                    System.arraycopy(rot, o, rotOff, o, 3);
                    hasRotOff[b] = true;
                }
                float progress = (renderTicks - lastRotTime[b]) / resetTicks;
                if (progress < 1f) {
                    alive = true;
                    tmp[0] = tmp[1] = tmp[2] = 0;
                    AnimMath.lerpRotation(progress, rotOff, o, tmp, 0, initRot, o, rot, o, rotScratch);
                } else {
                    rot[o] = rot[o + 1] = rot[o + 2] = 0;
                }
            }

            if (runPos[b]) {
                alive = true;
                runPos[b] = false;
                hasPosOff[b] = false;
            } else {
                if (!hasPosOff[b]) {
                    System.arraycopy(pos, o, posOff, o, 3);
                    hasPosOff[b] = true;
                }
                float progress = (renderTicks - lastPosTime[b]) / resetTicks;
                if (progress < 1f) {
                    alive = true;
                    for (int k = 0; k < 3; k++) {
                        pos[o + k] = AnimMath.lerp(progress, posOff[o + k], 0);
                    }
                } else {
                    pos[o] = pos[o + 1] = pos[o + 2] = 0;
                }
            }

            if (runScl[b]) {
                alive = true;
                runScl[b] = false;
                hasSclOff[b] = false;
            } else {
                if (!hasSclOff[b]) {
                    System.arraycopy(scl, o, sclOff, o, 3);
                    hasSclOff[b] = true;
                }
                float progress = (renderTicks - lastSclTime[b]) / resetTicks;
                if (progress < 1f) {
                    alive = true;
                    for (int k = 0; k < 3; k++) {
                        scl[o + k] = AnimMath.lerp(progress, sclOff[o + k], 1);
                    }
                } else {
                    scl[o] = scl[o + 1] = scl[o + 2] = 1;
                }
            }

            // commit
            int c = b * 9;
            System.arraycopy(rot, o, committed, c, 3);
            System.arraycopy(pos, o, committed, c + 3, 3);
            System.arraycopy(scl, o, committed, c + 6, 3);
            cachedRot[o] = cachedRot[o + 1] = cachedRot[o + 2] = 0;

            if (alive) {
                active[kept++] = b;
            } else {
                hasAnimation[b] = false;
            }
        }
        activeCount = kept;
    }

    /** Rotation delta of a bone as committed last (radians), component {@code axis}. */
    public float committedRotation(int bone, int axis) {
        return committed[bone * 9 + axis];
    }

    public float initialRotation(int bone, int axis) {
        return initRot[bone * 3 + axis];
    }

    /** The committed values of one bone (rotation delta, position in pixels, scale) into {@code out}. */
    public void committed(int bone, float[] out, int o) {
        System.arraycopy(committed, bone * 9, out, o, 9);
    }

    /** Number of bones still driven by an animation or easing back to rest. */
    public int activeBones() {
        return activeCount;
    }
}

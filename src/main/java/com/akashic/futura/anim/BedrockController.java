/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/geckolib3/core/controller/BedrockAnimationController.java and
 * src/main/java/com/elfmcys/ysm/expression/host/ControllerExecutionState.java, Apache License 2.0.
 * Changes: the per-bone blend queues are arrays indexed by bone over the players' own queues; the points are sampled
 * through the processor helpers into reusable arrays; controller-state sound effects play on entry (upstream 2.x has
 * the call but never reaches it).
 */
package com.akashic.futura.anim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.akashic.futura.molang.MolangExpression;

/**
 * A Bedrock animation controller from the model's files: a state machine over {@link ControllerData}.
 * <ul>
 * <li>It starts in {@code initial_state} (default {@code default}). Each frame the current state's transitions are
 * checked in declaration order and the first true one wins; a state that plays nothing is left at once in the same
 * frame if one of its transitions holds, never visiting a state twice in a frame.</li>
 * <li>A state plays all its animations at once, each with an optional condition, summed by their
 * {@code blend_weight}; on entry each restarts from the current pose over the state's {@code blend_transition}.</li>
 * <li>{@code on_exit} of the old state and {@code on_entry} of the new one run on a real transition, and the state's
 * {@code sound_effects} start; the sounds the controller started before stop.</li>
 * <li>{@code ysm-builtin} plays nothing here: a {@link HybridController} hands the frame to its coded controller.
 * {@code ysm-entry-<x>} runs the controller {@code <name>.<x>} (nested up to five levels) while in the state.</li>
 * </ul>
 */
public final class BedrockController implements AnimationController {

    private static final int MAX_HIERARCHY_DEPTH = 5;

    private final String name;
    private final AnimationLibrary library;
    private final float[] committed;
    private final AnimationPlayer.BoneIndex bones;
    private final Function<String, ControllerData> definitions;
    private final float initTransitionSeconds;
    private final SoundScope sounds = new SoundScope();

    private ControllerData data;
    private ControllerData.StateData state;
    private final List<Holder> players = new ArrayList<>();
    private int activePlayers;

    /** Per bone: the holders and queues blended into it; built after a transition. */
    private final List<List<Entry>> boneEntries;
    private final List<Integer> activeBones = new ArrayList<>();
    private boolean dirty;

    private String hierarchy;
    private int depth = 1;
    private BedrockController sub;
    private final Set<String> skip = new HashSet<>();

    private boolean allFinished;
    private boolean anyFinished;

    private final float[] value = new float[3];
    private final float[] target = new float[3];
    private final float[] offset = new float[3];

    /**
     * @param definitions the model's controllers by name (sub-controllers of {@code ysm-entry-*} states)
     * @param data        this controller's definition
     */
    BedrockController(String name, AnimationLibrary library, float[] committed, AnimationPlayer.BoneIndex bones,
        float initTransitionSeconds, Function<String, ControllerData> definitions, ControllerData data) {
        this.name = name;
        this.library = library;
        this.committed = committed;
        this.bones = bones;
        this.initTransitionSeconds = initTransitionSeconds;
        this.definitions = definitions;
        this.data = data;
        int n = bones.count();
        this.boneEntries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            boneEntries.add(null);
        }
    }

    private static final class Holder {

        final AnimationPlayer player;
        MolangExpression condition;
        boolean apply = true;

        Holder(AnimationPlayer player) {
            this.player = player;
        }

        void setCondition(MolangExpression condition) {
            this.condition = condition;
            if (condition == null) {
                apply = true;
            }
        }

        void evaluate(AnimationContext ctx) {
            if (condition != null) {
                apply = condition.evaluate(ctx)
                    .asBoolean();
            }
        }
    }

    private static final class Entry {

        final Holder holder;
        final BoneQueue queue;

        Entry(Holder holder, BoneQueue queue) {
            this.holder = holder;
            this.queue = queue;
        }
    }

    @Override
    public String name() {
        return name;
    }

    /** The current state, or null before the first frame. */
    public ControllerData.StateData currentState() {
        return state;
    }

    /** In {@code ysm-builtin}: the coded controller of the same name plays. */
    public boolean isBuiltinState() {
        return state != null && state.builtin;
    }

    @Override
    public String stateName() {
        if (state == null) {
            return "(null)";
        }
        if (state.subEntry != null && sub != null) {
            return sub.stateName();
        }
        return depth > 1 ? "[" + hierarchy + "] " + state.name : state.name;
    }

    @Override
    public boolean allAnimationsFinished() {
        return allFinished;
    }

    @Override
    public boolean anyAnimationFinished() {
        return anyFinished;
    }

    @Override
    public SoundScope soundScope() {
        return sounds;
    }

    /** Stops the sounds of the controller, its players and its sub-controller. */
    void stopAllSounds() {
        sounds.stopAll();
        for (Holder holder : players) {
            holder.player.stopSounds();
        }
        if (sub != null) {
            sub.stopAllSounds();
        }
    }

    @Override
    public PlaybackState state() {
        if (state != null && state.subEntry != null && sub != null) {
            return sub.state();
        }
        for (int i = 0; i < activePlayers; i++) {
            PlaybackState s = players.get(i).player.state();
            if (s != PlaybackState.IDLE) {
                return PlaybackState.RUNNING;
            }
        }
        return PlaybackState.IDLE;
    }

    @Override
    public void process(float renderTicks, AnimationContext ctx, boolean executeEvents) {
        if (data == null) {
            return;
        }
        AnimationController saved = ctx.controller;
        ctx.controller = this;
        try {
            skip.clear();
            boolean update = false;
            while (true) {
                if (updateState(ctx)) {
                    update = true;
                    if (activePlayers == 0) {
                        // an empty state may be left at once
                        continue;
                    }
                }
                break;
            }

            if (state != null && state.subEntry != null && depth != MAX_HIERARCHY_DEPTH) {
                if (update) {
                    String subHierarchy = depth > 1 ? hierarchy + "." + state.subEntry : state.subEntry;
                    ControllerData subData = definitions.apply(name + "." + subHierarchy);
                    if (subData != null) {
                        if (sub == null) {
                            sub = new BedrockController(
                                name,
                                library,
                                committed,
                                bones,
                                initTransitionSeconds,
                                definitions,
                                subData);
                        } else {
                            sub.data = subData;
                        }
                        sub.hierarchy = subHierarchy;
                        sub.depth = depth + 1;
                    }
                }
                if (sub != null) {
                    sub.process(renderTicks, ctx, executeEvents);
                }
                return;
            }

            for (int i = 0; i < activePlayers; i++) {
                Holder holder = players.get(i);
                holder.evaluate(ctx);
                holder.player.process(renderTicks, ctx, executeEvents && holder.apply);
            }
            if (dirty) {
                rebuildEntries();
            }
        } finally {
            ctx.controller = saved;
        }
    }

    /** Upstream {@code updateState}: enters the initial state, or follows the first transition that holds. */
    private boolean updateState(AnimationContext ctx) {
        if (state == null) {
            ControllerData.StateData initial = data.states.get(data.initialState);
            if (initial == null) {
                return false;
            }
            skip.add(initial.name);
            transition(initial, ctx);
            return true;
        }
        int applied = 0;
        anyFinished = false;
        allFinished = true;
        for (int i = 0; i < activePlayers; i++) {
            Holder holder = players.get(i);
            if (holder.apply) {
                applied++;
                if (holder.player.currentAnimFinished()) {
                    anyFinished = true;
                } else {
                    allFinished = false;
                }
            }
        }
        if (applied == 0) {
            anyFinished = true;
        }
        for (int i = 0; i < state.targets.length; i++) {
            if (!holds(state.conditions[i], ctx)) {
                continue;
            }
            ControllerData.StateData next = data.states.get(state.targets[i]);
            if (next == null || !skip.add(next.name)) {
                return false;
            }
            transition(next, ctx);
            return true;
        }
        return false;
    }

    private static boolean holds(MolangExpression condition, AnimationContext ctx) {
        try {
            return condition != null && condition.evaluate(ctx)
                .asBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void run(MolangExpression program, AnimationContext ctx) {
        if (program != null) {
            try {
                program.evaluate(ctx);
            } catch (RuntimeException ignored) {
                // fail-soft
            }
        }
    }

    /** Upstream {@code transition}: exit and entry programs, state sounds, then the new state's players. */
    private void transition(ControllerData.StateData next, AnimationContext ctx) {
        if (next == null && state == null) {
            return;
        }
        sounds.stopAll();
        if (state != null) {
            if (state.subEntry != null && sub != null) {
                sub.exitTo(ctx);
            }
            run(state.onExit, ctx);
        }
        if (next != null) {
            run(next.onEntry, ctx);
            for (String sound : next.soundEffects) {
                sounds.play(ctx.soundBackend(), 0, sound, false, 1, 1, false);
            }
        }
        state = next;
        clearEntries();
        dirty = true;

        int size = next == null || next.builtin || next.subEntry != null ? 0 : next.animations.length;
        while (players.size() < size) {
            players.add(new Holder(new AnimationPlayer(library, committed, bones, initTransitionSeconds, false)));
        }
        for (int i = size; i < activePlayers; i++) {
            players.get(i).player.reset();
        }
        activePlayers = size;
        for (int i = 0; i < size; i++) {
            Holder holder = players.get(i);
            holder.setCondition(next.animationConditions[i]);
            holder.player.setBeginningTransition(next.blend);
            holder.player.indicateReload();
            holder.player.setAnimation(next.animations[i], null);
        }
    }

    /**
     * The parent left the {@code ysm-entry-*} state: run this controller's exit (upstream {@code transition(null)}).
     */
    private void exitTo(AnimationContext ctx) {
        AnimationController saved = ctx.controller;
        ctx.controller = this;
        try {
            transition(null, ctx);
        } finally {
            ctx.controller = saved;
        }
    }

    private void clearEntries() {
        for (int b : activeBones) {
            boneEntries.set(b, null);
        }
        activeBones.clear();
    }

    private void rebuildEntries() {
        clearEntries();
        for (int i = 0; i < activePlayers; i++) {
            Holder holder = players.get(i);
            for (BoneQueue q : holder.player.activeQueues()) {
                List<Entry> list = boneEntries.get(q.bone);
                if (list == null) {
                    list = new ArrayList<>(2);
                    boneEntries.set(q.bone, list);
                    activeBones.add(q.bone);
                }
                list.add(new Entry(holder, q));
            }
        }
        dirty = false;
    }

    @Override
    public void visit(AnimationProcessor processor, AnimationContext ctx) {
        if (state == null) {
            return;
        }
        if (state.subEntry != null && sub != null) {
            AnimationController saved = ctx.controller;
            ctx.controller = sub;
            try {
                sub.visit(processor, ctx);
            } finally {
                ctx.controller = saved;
            }
            return;
        }
        float savedTime = ctx.animTime;
        try {
            for (int i = 0; i < activeBones.size(); i++) {
                int b = activeBones.get(i);
                List<Entry> entries = boneEntries.get(b);
                if (entries == null || !shouldApply(entries)) {
                    continue;
                }
                processor.touch(b);
                pollRotation(processor, ctx, b, entries);
                pollLinear(processor, ctx, b, entries, 1);
                pollLinear(processor, ctx, b, entries, 2);
            }
        } finally {
            ctx.animTime = savedTime;
        }
    }

    private static boolean shouldApply(List<Entry> entries) {
        for (Entry e : entries) {
            if (e.holder.apply) {
                return true;
            }
        }
        return false;
    }

    /**
     * Upstream {@code BlendBoneAnimationQueue.pollRotationPoint}: the applying players' values summed by weight (a
     * player blending out weighs {@code 1 - progress} and the result fades by the smallest progress), or while the
     * state blends in, the summed targets interpolated from the pose the bone had (every player starts together).
     */
    private void pollRotation(AnimationProcessor processor, AnimationContext ctx, int b, List<Entry> entries) {
        float[] t = target;
        t[0] = t[1] = t[2] = 0;
        float ending = 1;
        boolean active = false, first = true, begin = false;
        float progress = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            BoneQueue q = e.queue;
            if (!e.holder.apply || !q.active || q.kind[0] == BoneQueue.NONE) {
                continue;
            }
            active = true;
            // as the coded path: query.anim_time is the sampled player's time
            ctx.animTime = e.holder.player.animTime();
            if (first) {
                first = false;
                if (q.kind[0] == BoneQueue.BEGIN) {
                    begin = true;
                    System.arraycopy(q.transitionOffset, 0, offset, 0, 3);
                    progress = q.progress[0];
                    ending = 0;
                }
            }
            float w = q.blendWeight();
            if (!begin) {
                ending = Math.min(ending, sample(processor, ctx, q, 0, value));
                if (q.kind[0] == BoneQueue.END) {
                    w *= 1 - q.progress[0];
                }
                fma(t, w, value);
            } else if (progress <= -0.00001f || progress >= 0.00001f) {
                beginTarget(processor, ctx, q, 0, value);
                fma(t, w, value);
            } else {
                processor.applyRotation(b, offset, 0);
                return;
            }
        }
        if (active) {
            if (begin) {
                processor.lerpRotation(b, progress, offset, 0, t, t);
            }
            processor.applyRotation(b, t, ending);
        }
    }

    /** Position (channel 1, summed) or scale (channel 2, multiplied), as {@code pollPositionPoint/pollScalePoint}. */
    private void pollLinear(AnimationProcessor processor, AnimationContext ctx, int b, List<Entry> entries, int c) {
        boolean scale = c == 2;
        float[] t = target;
        t[0] = t[1] = t[2] = scale ? 1 : 0;
        float ending = 1;
        boolean active = false, first = true, begin = false;
        float progress = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            BoneQueue q = e.queue;
            if (!e.holder.apply || !q.active || q.kind[c] == BoneQueue.NONE) {
                continue;
            }
            active = true;
            ctx.animTime = e.holder.player.animTime();
            if (first) {
                first = false;
                if (q.kind[c] == BoneQueue.BEGIN) {
                    begin = true;
                    System.arraycopy(q.transitionOffset, c * 3, offset, 0, 3);
                    progress = q.progress[c];
                    ending = 0;
                }
            }
            float w = q.blendWeight();
            if (!begin) {
                ending = Math.min(ending, sample(processor, ctx, q, c, value));
                if (q.kind[c] == BoneQueue.END) {
                    w *= 1 - q.progress[c];
                }
                if (!scale) {
                    fma(t, w, value);
                } else if (w == 1f) {
                    mul(t, value);
                } else {
                    weightedMul(t, value, w);
                }
            } else if (progress <= -0.00001f || progress >= 0.00001f) {
                beginTarget(processor, ctx, q, c, value);
                if (scale) {
                    weightedMul(t, value, w);
                } else {
                    fma(t, w, value);
                }
            } else {
                if (scale) {
                    processor.applyScale(b, offset, 0);
                } else {
                    processor.applyPosition(b, offset, 0);
                }
                return;
            }
        }
        if (active) {
            if (begin) {
                for (int k = 0; k < 3; k++) {
                    t[k] = AnimMath.lerp(progress, offset[k], t[k]);
                }
            }
            if (scale) {
                processor.applyScale(b, t, ending);
            } else {
                processor.applyPosition(b, t, ending);
            }
        }
    }

    /**
     * A point's value ({@code getLerpPoint}): keyframe, ending source, or (a player that started late, which upstream
     * assumes never happens) its own interpolated beginning transition.
     *
     * @return the ending progress of the point (0 unless blending out)
     */
    private static float sample(AnimationProcessor processor, AnimationContext ctx, BoneQueue q, int c, float[] out) {
        switch (q.kind[c]) {
            case BoneQueue.KEY:
                processor.sampleKey(q, c, ctx, out);
                return 0;
            case BoneQueue.END:
                processor.endValue(q, c, out);
                return q.progress[c];
            default:
                processor.transitionTarget(q, c, ctx, out);
                for (int k = 0; k < 3; k++) {
                    out[k] = AnimMath.lerp(q.progress[c], q.transitionOffset[c * 3 + k], out[k]);
                }
                System.arraycopy(out, 0, q.lastLerp, c * 3, 3);
                q.hasLastLerp[c] = true;
                return 0;
        }
    }

    /** {@code getTransitionDst}; a player not blending in contributes its plain value. */
    private static void beginTarget(AnimationProcessor processor, AnimationContext ctx, BoneQueue q, int c,
        float[] out) {
        if (q.kind[c] == BoneQueue.BEGIN) {
            processor.transitionTarget(q, c, ctx, out);
        } else {
            sample(processor, ctx, q, c, out);
        }
    }

    private static void fma(float[] t, float w, float[] v) {
        t[0] += w * v[0];
        t[1] += w * v[1];
        t[2] += w * v[2];
    }

    private static void mul(float[] t, float[] v) {
        t[0] *= v[0];
        t[1] *= v[1];
        t[2] *= v[2];
    }

    private static void weightedMul(float[] t, float[] v, float w) {
        t[0] *= AnimMath.weightedScale(v[0], w);
        t[1] *= AnimMath.weightedScale(v[1], w);
        t[2] *= AnimMath.weightedScale(v[2], w);
    }
}

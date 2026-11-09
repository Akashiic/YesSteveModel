package com.akashic.futura.anim;

import com.akashic.futura.molang.MolangContext;
import com.akashic.futura.molang.MolangVariables;

/**
 * The Molang context of one animated entity, with the playback state the runtime installs while it evaluates: the
 * animation time of the clip being sampled ({@code query.anim_time}) and the controller being processed. The player
 * context ({@link PlayerMolangContext}) extends it with the {@code query.*}, {@code ysm.*} and {@code ctrl.*} names.
 */
public abstract class AnimationContext implements MolangContext {

    private final MolangVariables variables = new MolangVariables();

    /** Seconds into the clip being evaluated (upstream {@code AnimationExecutionState.animTime}). */
    float animTime;
    /** The controller being processed or sampled, or null. */
    AnimationController controller;

    @Override
    public MolangVariables variables() {
        return variables;
    }

    /** {@code query.anim_time}. */
    public float animTime() {
        return animTime;
    }

    public AnimationController controller() {
        return controller;
    }
}

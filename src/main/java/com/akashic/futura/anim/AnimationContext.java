package com.akashic.futura.anim;

import com.akashic.futura.molang.MolangContext;
import com.akashic.futura.molang.MolangVariables;

/**
 * The Molang context of one animated entity, with the playback state the runtime installs while it evaluates: the
 * animation time of the clip being sampled ({@code query.anim_time}), the controller being processed and the animation
 * player whose timeline runs (upstream's controller and animation execution states). The player context
 * ({@link PlayerMolangContext}) extends it with the {@code query.*}, {@code ysm.*}, {@code ctrl.*} and {@code fn.*}
 * names.
 */
public abstract class AnimationContext implements MolangContext {

    private final MolangVariables variables;
    /** The entity's own sound scope ({@code ysm.play_sound} with mode bit 2, or outside any controller). */
    final SoundScope globalSounds = new SoundScope();
    private SoundBackend soundBackend = SoundBackend.NONE;

    /** Seconds into the clip being evaluated (upstream {@code AnimationExecutionState.animTime}). */
    float animTime;
    /** The controller being processed or sampled, or null. */
    AnimationController controller;
    /** The animation player whose timeline or predicate is running, or null (its sound scope comes first). */
    AnimationPlayer player;

    protected AnimationContext() {
        this(new MolangVariables());
    }

    /** @param variables the entity's {@code v.*} store (e.g. one that shares roaming variables with another) */
    protected AnimationContext(MolangVariables variables) {
        this.variables = variables;
    }

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

    /** Where the entity's sounds play. */
    public SoundBackend soundBackend() {
        return soundBackend;
    }

    public void setSoundBackend(SoundBackend backend) {
        soundBackend = backend == null ? SoundBackend.NONE : backend;
    }

    /**
     * Upstream {@code ModelBuiltins.soundManager}: the scope of the animation running, else the Bedrock controller's,
     * else the entity's own; {@code global} always the entity's own.
     */
    SoundScope soundScope(boolean global) {
        if (!global) {
            if (player != null) {
                return player.sounds;
            }
            if (controller != null) {
                SoundScope scope = controller.soundScope();
                if (scope != null) {
                    return scope;
                }
            }
        }
        return globalSounds;
    }
}

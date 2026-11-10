package com.akashic.futura.mixin.mixins;

import net.minecraft.entity.projectile.EntityArrow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether an arrow is stuck in a block (the {@code ground} animation of a projectile replacement). */
@Mixin(EntityArrow.class)
public interface EntityArrowAccessor {

    @Accessor("inGround")
    boolean futura3d$inGround();
}

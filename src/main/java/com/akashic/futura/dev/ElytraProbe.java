package com.akashic.futura.dev;

import net.minecraft.entity.player.EntityPlayer;

/** Development only: what Et Futurum Requiem says about a player's glide, for the harness log. */
final class ElytraProbe {

    private ElytraProbe() {}

    static Object flying(EntityPlayer p) {
        return call(p, "etfu$isElytraFlying");
    }

    static Object ticks(EntityPlayer p) {
        return call(p, "etfu$getTicksElytraFlying");
    }

    private static Object call(EntityPlayer p, String name) {
        try {
            return p.getClass()
                .getMethod(name)
                .invoke(p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }
}

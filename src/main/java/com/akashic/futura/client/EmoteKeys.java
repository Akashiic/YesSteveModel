/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/input/AnimationRouletteKey.java, ExtraAnimationKey.java and
 * src/main/java/com/elfmcys/ysm/client/event/PlayerMoveEvent.java, Apache License 2.0.
 * Changes: 1.7.10 key bindings have no modifiers, so the roulette lock key (Alt+L upstream) starts unbound; local
 * emotes only (no server round trip yet).
 */
package com.akashic.futura.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovementInput;

import org.lwjgl.input.Keyboard;

import com.akashic.futura.client.gui.AnimationRouletteScreen;
import com.akashic.futura.emote.RouletteMenu;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Key bindings of the extra (emote) animations: the roulette ({@code Z}, as upstream), extra animations 0-7 (unbound)
 * and the roulette lock (unbound). Moving, jumping or sneaking stops the emote unless the lock is on.
 */
public final class EmoteKeys {

    public static final String CATEGORY = "key.categories.futura3d";
    public static final KeyBinding ROULETTE = new KeyBinding(
        "key.futura3d.animation_roulette",
        Keyboard.KEY_Z,
        CATEGORY);
    public static final KeyBinding LOCK = new KeyBinding("key.futura3d.lock_roulette", Keyboard.KEY_NONE, CATEGORY);
    public static final KeyBinding[] EXTRA = new KeyBinding[RouletteMenu.PER_PAGE];

    static {
        for (int i = 0; i < EXTRA.length; i++) {
            EXTRA[i] = new KeyBinding("key.futura3d.extra_animation." + i, Keyboard.KEY_NONE, CATEGORY);
        }
    }

    private static boolean locked;

    private EmoteKeys() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(ROULETTE);
        ClientRegistry.registerKeyBinding(LOCK);
        for (KeyBinding key : EXTRA) {
            ClientRegistry.registerKeyBinding(key);
        }
        FMLCommonHandler.instance()
            .bus()
            .register(new EmoteKeys());
    }

    /** Roulette lock (upstream {@code PlayerMoveEvent.LOCK_EXTRA_ANIMATION}): movement does not stop the emote. */
    public static boolean isLocked() {
        return locked;
    }

    public static void switchLock() {
        locked = !locked;
    }

    /** Upstream {@code PlayerMoveEvent.isMoveKey}: a movement, jump or sneak input this tick. */
    public static boolean isMoving(EntityClientPlayerMP player) {
        MovementInput input = player.movementInput;
        return input != null && (Math.abs(input.moveStrafe) > 1.0E-5f || Math.abs(input.moveForward) > 1.0E-5f
            || input.jump
            || input.sneak);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.currentScreen != null) {
            return;
        }
        while (LOCK.isPressed()) {
            switchLock();
        }
        RouletteMenu menu = LocalEmotes.INSTANCE.menu();
        if (menu == null || !PlayerModels.INSTANCE.active()) {
            return;
        }
        while (ROULETTE.isPressed()) {
            if (!menu.isEmpty()) {
                mc.displayGuiScreen(new AnimationRouletteScreen(menu));
                return;
            }
        }
        for (int i = 0; i < EXTRA.length; i++) {
            while (EXTRA[i].isPressed()) {
                if (!isMoving(player)) {
                    LocalEmotes.INSTANCE.apply(menu.key(i));
                }
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || locked) {
            return;
        }
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player != null && isMoving(player) && LocalEmotes.INSTANCE.isPlaying()) {
            LocalEmotes.INSTANCE.stop();
        }
    }
}

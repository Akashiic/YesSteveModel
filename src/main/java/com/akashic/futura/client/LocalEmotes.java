package com.akashic.futura.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.ChatComponentTranslation;

import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.client.gui.AnimationRouletteScreen;
import com.akashic.futura.emote.EmoteListener;
import com.akashic.futura.emote.RouletteMenu;
import com.akashic.futura.model.LoadedModel;

/**
 * The local player's extra (emote) animations: played at once on the local animator, then announced to the
 * {@link EmoteListener}s (the networking seam: nothing is sent to other players yet). Also keeps the roulette menu of
 * the current model, so its open page survives closing and reopening.
 */
public final class LocalEmotes {

    public static final LocalEmotes INSTANCE = new LocalEmotes();

    private final List<EmoteListener> listeners = new CopyOnWriteArrayList<>();
    private LoadedModel menuModel;
    private RouletteMenu menu;

    private LocalEmotes() {}

    /** Phase 4: the network layer listens here to send the local player's emotes to the server. */
    public void addListener(EmoteListener listener) {
        listeners.add(listener);
    }

    public void removeListener(EmoteListener listener) {
        listeners.remove(listener);
    }

    /** The roulette menu of the current model (rebuilt when the model changes), or null without a model. */
    public RouletteMenu menu() {
        LoadedModel model = PlayerModels.INSTANCE.model();
        if (model == null) {
            return null;
        }
        if (model != menuModel) {
            menuModel = model;
            menu = new RouletteMenu(model.manifest.properties);
        }
        return menu;
    }

    private static PlayerAnimator localAnimator() {
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || !PlayerModels.INSTANCE.active()) {
            return null;
        }
        return PlayerModels.INSTANCE.animator(player);
    }

    /**
     * Plays extra animation {@code name} on the local player.
     *
     * @return false if there is no model or neither it nor the default model has the animation
     */
    public boolean play(String name) {
        PlayerAnimator animator = localAnimator();
        if (animator == null || !animator.playExtra(name)) {
            return false;
        }
        for (EmoteListener listener : listeners) {
            listener.played(name);
        }
        return true;
    }

    /** Stops the local player's extra animation, if one plays. */
    public void stop() {
        PlayerAnimator animator = localAnimator();
        if (animator == null || !animator.isPlayingExtra()) {
            return;
        }
        animator.stopExtra();
        for (EmoteListener listener : listeners) {
            listener.stopped();
        }
    }

    public boolean isPlaying() {
        PlayerAnimator animator = localAnimator();
        return animator != null && animator.isPlayingExtra();
    }

    /** Carries out a roulette or extra-key action. */
    public void apply(RouletteMenu.Action action) {
        Minecraft mc = Minecraft.getMinecraft();
        switch (action.type) {
            case PLAY:
                play(action.name);
                mc.displayGuiScreen(null);
                break;
            case STOP:
                stop();
                break;
            case REFRESH:
                mc.displayGuiScreen(new AnimationRouletteScreen(menu()));
                break;
            case CLOSE:
                mc.displayGuiScreen(null);
                break;
            case TOO_DEEP:
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentTranslation("gui.futura3d.roulette.too_long"));
                }
                break;
            default:
                break;
        }
    }
}

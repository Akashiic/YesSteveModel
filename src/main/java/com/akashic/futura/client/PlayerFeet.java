package com.akashic.futura.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Where a player's feet are in a frame, relative to the camera, without the heights 1.7.10 adds for its biped.
 * {@code RenderPlayer.doRender} draws the biped at {@code posY - yOffset}, where {@code posY} of the local player is
 * lowered by {@code ySize} (0.2 × 0.4 = 0.08 blocks while sneaking, and the eased step-up), other players are drawn
 * 0.125 lower while sneaking, and other mods move that height for their biped (the Akashic Player Backport lifts a
 * crouching biped by its leg pivot change). A model is placed as upstream places it ({@code RenderOffset}): from the
 * real feet, the bounding box's bottom ({@code posY - yOffset + ySize}), interpolated like the render position.
 */
public final class PlayerFeet {

    static final PlayerFeet INSTANCE = new PlayerFeet();

    /** The local player's {@code ySize} at the end of the last tick: the one {@code lastTickPosY} was lowered by. */
    private float lastYSize;
    private EntityPlayer lastPlayer;

    private PlayerFeet() {}

    void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            // before this tick's update: lastTickPosY is about to become posY, lowered by this ySize
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            lastPlayer = player;
            lastYSize = player == null ? 0 : player.ySize;
        }
    }

    /** The feet's height in the frame, relative to the camera ({@code RenderManager.renderPosY}). */
    double y(EntityPlayer player, float pt) {
        double posY = player.lastTickPosY + (player.posY - player.lastTickPosY) * pt;
        return posY + ySize(player, pt) - player.yOffset - RenderManager.renderPosY;
    }

    /** The {@code ySize} the interpolated position is lowered by in the frame. */
    float ySize(EntityPlayer player, float pt) {
        float last = player == lastPlayer ? lastYSize : player.ySize;
        return last + (player.ySize - last) * pt;
    }
}

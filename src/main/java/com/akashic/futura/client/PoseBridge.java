package com.akashic.futura.client;

import java.lang.reflect.Method;

import net.minecraft.entity.player.EntityPlayer;

import com.akashic.futura.Futura3D;

/**
 * The pose Akashic Player Backport ({@code akashicplayer}) gives a player: it backports the 1.13+ swimming and crawling
 * poses (hitbox, movement, server sync) and draws them on the vanilla biped, which a player model replaces. Read by
 * reflection ({@code PoseController.getControlledPose}), so neither mod needs the other.
 */
final class PoseBridge {

    enum Pose {
        NONE,
        SWIMMING,
        CRAWLING
    }

    private static boolean resolved;
    private static Method getControlledPose;

    private PoseBridge() {}

    /** Whether the backport mod is there (its pose then decides swimming and crawling). */
    static boolean present() {
        resolve();
        return getControlledPose != null;
    }

    static Pose pose(EntityPlayer player) {
        resolve();
        if (getControlledPose == null) {
            return Pose.NONE;
        }
        try {
            Object pose = getControlledPose.invoke(null, player);
            if (pose instanceof Enum) {
                String name = ((Enum<?>) pose).name();
                if ("SWIMMING".equals(name)) {
                    return Pose.SWIMMING;
                }
                if ("CRAWLING".equals(name)) {
                    return Pose.CRAWLING;
                }
            }
            return Pose.NONE;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Futura3D.LOG.warn("Akashic Player Backport's pose is unreadable; swimming falls back to our own rule", e);
            getControlledPose = null;
            return Pose.NONE;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> controller = Class
                .forName("dev.akashic.playerbackport.pose.PoseController", true, PoseBridge.class.getClassLoader());
            getControlledPose = controller.getMethod("getControlledPose", EntityPlayer.class);
            Futura3D.LOG.info("Akashic Player Backport found: its swimming and crawling poses drive the player models");
        } catch (ClassNotFoundException e) {
            // not installed: our own swimming rule
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Futura3D.LOG.warn("Akashic Player Backport is present but its poses are unreachable", e);
        }
    }
}

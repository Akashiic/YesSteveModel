package com.akashic.futura.server;

import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.akashic.futura.Futura3D;

/**
 * Where the model permission nodes are checked. On a Thermos/Crucible server, Bukkit's
 * {@code Player.hasPermission} (so a permission plugin such as LuckPerms decides, groups and wildcards included; Bukkit
 * gives operators every node nobody declared). Bukkit is reached by reflection: the mod also runs on plain Forge.
 * Without Bukkit: on a dedicated server operators have every node and the others none; in singleplayer (and its LAN
 * guests) everyone has every node.
 */
final class ServerPermissions {

    private ServerPermissions() {}

    /** The backend is chosen on the first check, when players are online (Bukkit is surely up by then). */
    static ModelServer.Permissions lazy() {
        return new ModelServer.Permissions() {

            private ModelServer.Permissions backend;

            @Override
            public boolean has(UUID player, String node) {
                if (backend == null) {
                    backend = create(MinecraftServer.getServer());
                }
                return backend.has(player, node);
            }
        };
    }

    static ModelServer.Permissions create(MinecraftServer server) {
        ModelServer.Permissions bukkit = bukkit();
        if (bukkit != null) {
            Futura3D.LOG.info("Model permissions: Bukkit (futura3d.playermodel.<model>)");
            return bukkit;
        }
        if (server != null && !server.isDedicatedServer()) {
            Futura3D.LOG.info("Model permissions: singleplayer, every model allowed");
            return (player, node) -> true;
        }
        Futura3D.LOG.info("Model permissions: no Bukkit, operators have every model node and other players none");
        return (player, node) -> {
            EntityPlayerMP p = ForgeModelServer.player(player);
            MinecraftServer mc = MinecraftServer.getServer();
            return p != null && mc != null
                && mc.getConfigurationManager()
                    .func_152596_g(p.getGameProfile());
        };
    }

    private static ModelServer.Permissions bukkit() {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", true, ServerPermissions.class.getClassLoader());
            Method getServer = bukkit.getMethod("getServer");
            if (getServer.invoke(null) == null) {
                return null;
            }
            Method getPlayer = bukkit.getMethod("getPlayer", UUID.class);
            Class<?> permissible = Class
                .forName("org.bukkit.permissions.Permissible", true, ServerPermissions.class.getClassLoader());
            Method hasPermission = permissible.getMethod("hasPermission", String.class);
            return new ModelServer.Permissions() {

                private boolean warned;

                @Override
                public boolean has(UUID player, String node) {
                    try {
                        Object p = getPlayer.invoke(null, player);
                        return p != null && Boolean.TRUE.equals(hasPermission.invoke(p, node));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        if (!warned) {
                            warned = true;
                            Futura3D.LOG.warn("Could not check a Bukkit permission ({}): models stay locked", node, e);
                        }
                        return false;
                    }
                }
            };
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Futura3D.LOG.warn("Bukkit is present but its permissions are unreachable", e);
            return null;
        }
    }
}

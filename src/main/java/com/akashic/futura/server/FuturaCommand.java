package com.akashic.futura.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.akashic.futura.net.Protocol;
import com.mojang.authlib.GameProfile;

/**
 * {@code /futura3d list | reload | set <player> <model|default> [texture] | auth <player> add|remove <model> |
 * access <player> | play <player> <animation|stop>}, for operators (permission level 2).
 */
public final class FuturaCommand extends CommandBase {

    private static final String USAGE = "/futura3d list | reload | set <player> <model|default|builtin:<folder>> [texture]"
        + " | auth <player> add|remove <model> | access <player> | play <player> <animation|stop>";

    @Override
    public String getCommandName() {
        return "futura3d";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        ModelServer server = ForgeModelServer.current();
        if (server == null) {
            throw new CommandException("The model server is not running");
        }
        if (args.length == 0) {
            throw new WrongUsageException(USAGE);
        }
        try {
            switch (args[0]) {
                case "list":
                    for (String line : server.list()) {
                        tell(sender, line, null);
                    }
                    break;
                case "reload":
                    tell(sender, "Reloading the server models...", null);
                    server.reload(done -> tell(sender, done, EnumChatFormatting.GREEN));
                    break;
                case "set": {
                    if (args.length < 3 || args.length > 4) {
                        throw new WrongUsageException(
                            "/futura3d set <player> <model|default|builtin:<folder>> [texture]");
                    }
                    java.util.UUID target = uuid(sender, args[1]);
                    String what = server.set(target, args[2], args.length > 3 ? args[3] : null);
                    String status = server.handshakeStatus(target);
                    tell(sender, args[1] + " now uses " + what, EnumChatFormatting.GREEN);
                    if (!status.startsWith("protocol " + com.akashic.futura.net.Protocol.VERSION)) {
                        // the choice is saved, but his client cannot show it yet: say why
                        tell(sender, args[1] + ": " + status, EnumChatFormatting.YELLOW);
                    }
                    com.akashic.futura.Futura3D.LOG
                        .info("/futura3d set {} {}: {} ({})", args[1], what, status, sender.getCommandSenderName());
                    break;
                }
                case "auth": {
                    if (args.length != 4 || !args[2].equals("add") && !args[2].equals("remove")) {
                        throw new WrongUsageException("/futura3d auth <player> add|remove <model>");
                    }
                    String what = server.auth(uuid(sender, args[1]), args[3], args[2].equals("add"));
                    tell(sender, args[1] + ": " + what, EnumChatFormatting.GREEN);
                    break;
                }
                case "access": {
                    if (args.length != 2) {
                        throw new WrongUsageException("/futura3d access <player>");
                    }
                    for (String line : server.accessReport(uuid(sender, args[1]))) {
                        tell(
                            sender,
                            line,
                            line.startsWith("[locked]") ? EnumChatFormatting.RED
                                : line.startsWith("[allowed]") ? EnumChatFormatting.GREEN : null);
                    }
                    break;
                }
                case "play": {
                    if (args.length != 3) {
                        throw new WrongUsageException("/futura3d play <player> <animation|stop>");
                    }
                    EntityPlayerMP target = getPlayer(sender, args[1]);
                    server.play(target.getUniqueID(), args[2].equals("stop") ? null : args[2]);
                    tell(sender, args[2].equals("stop") ? "Stopped" : "Playing " + args[2], EnumChatFormatting.GREEN);
                    break;
                }
                default:
                    throw new WrongUsageException(USAGE);
            }
        } catch (ModelServer.CommandError e) {
            throw new CommandException(e.getMessage());
        }
    }

    /** An online player (selectors work), a known offline name, or a UUID. */
    private static UUID uuid(ICommandSender sender, String name) {
        MinecraftServer mc = MinecraftServer.getServer();
        EntityPlayerMP online = mc.getConfigurationManager()
            .func_152612_a(name);
        if (online != null) {
            return online.getUniqueID();
        }
        if (name.startsWith("@")) {
            return getPlayer(sender, name).getUniqueID();
        }
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException notUuid) {
            GameProfile profile = mc.func_152358_ax()
                .func_152655_a(name);
            if (profile == null || profile.getId() == null) {
                throw new CommandException("Unknown player " + name);
            }
            return profile.getId();
        }
    }

    private static void tell(ICommandSender sender, String text, EnumChatFormatting color) {
        ChatComponentText msg = new ChatComponentText(text);
        if (color != null) {
            msg.getChatStyle()
                .setColor(color);
        }
        sender.addChatMessage(msg);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "list", "reload", "set", "auth", "access", "play");
        }
        if (args.length == 2 && !args[0].equals("list") && !args[0].equals("reload")) {
            return getListOfStringsMatchingLastWord(
                args,
                MinecraftServer.getServer()
                    .getAllUsernames());
        }
        ModelServer server = ForgeModelServer.current();
        List<String> names = new ArrayList<>();
        if (server != null) {
            for (ServerModel m : server.snapshot().models) {
                names.add(m.name);
            }
        }
        if (args.length == 3 && args[0].equals("set")) {
            names.add("default");
            names.add(Protocol.BUILTIN + "default");
            return getListOfStringsFromIterableMatchingLastWord(args, names);
        }
        if (args.length == 3 && args[0].equals("auth")) {
            return getListOfStringsMatchingLastWord(args, "add", "remove");
        }
        if (args.length == 4 && args[0].equals("auth")) {
            return getListOfStringsFromIterableMatchingLastWord(args, names);
        }
        if (args.length == 3 && args[0].equals("play")) {
            return getListOfStringsMatchingLastWord(args, "stop");
        }
        return null;
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 1 && args.length > 0 && !args[0].equals("list") && !args[0].equals("reload");
    }
}

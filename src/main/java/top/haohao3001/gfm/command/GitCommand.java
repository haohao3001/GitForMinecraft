package top.haohao3001.gfm.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Predicate;

public class GitCommand {
    public static void register(Plugin plugin,Commands commands){
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("gfm")
                .then(buildNotifyCommand())
                .then(buildReloadConfigCommand(plugin))
                .then(buildWrapCommand());
        commands.register(command.build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildNotifyCommand(){
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("notify")
                .requires(isServer())
                .then(Commands.argument("text", ArgumentTypes.component())
                        .executes(context -> {
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (!player.hasPermission("gfm.notify.receive")) continue;
                                Component component = context.getArgument("text",Component.class);
                                player.sendMessage(component);
                            }
                            return 0;
                        })
                );
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildReloadConfigCommand(Plugin plugin){
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("reloadconfig")
                .requires(hasPermission("gitforminecraft.reloadconfig"))
                .executes(context -> {
                    plugin.reloadConfig();
                    context.getSource().getSender().sendMessage(Component.text("已重载配置文件"));
                    return 0;
                });
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildWrapCommand(){
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("wrapcommand")
                .requires(isServer())
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(context -> {
                            String wrappedCommand = StringArgumentType.getString(context,"command");
                            CommandSender executer = Bukkit.createCommandSender(component -> {
                                for (Player player : Bukkit.getOnlinePlayers()) {
                                    if (!player.hasPermission("gitforminecraft.notify.receive")) continue;
                                    player.sendMessage(component);
                                }
                            });
                            Bukkit.dispatchCommand(executer,wrappedCommand);
                            return 0;
                        })
                );
        return command;
    }

    private static Predicate<CommandSourceStack> hasPermission(String permission){
        return commandSourceStack -> commandSourceStack.getSender().hasPermission(permission);
    }

    private static Predicate<CommandSourceStack> isServer(){
        return commandSourceStack -> {
            CommandSender sender = commandSourceStack.getSender();
            return sender instanceof ConsoleCommandSender;
        };
    }
}

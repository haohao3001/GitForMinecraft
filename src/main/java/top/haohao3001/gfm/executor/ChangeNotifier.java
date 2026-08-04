package top.haohao3001.gfm.executor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sends file change notifications to online players with the gfm.notify permission.
 */
public class ChangeNotifier {

    private ChangeNotifier() {}

    /**
     * Notify all players with gfm.notify permission about file changes from a webhook push.
     */
    public static void notifyChanges(String author, Set<String> added, Set<String> modified, Set<String> removed, List<String> commitMessages) {
        if (added.isEmpty() && modified.isEmpty() && removed.isEmpty()) return;

        Component header = Component.text()
                .append(Component.text("── ", NamedTextColor.DARK_GRAY, TextDecoration.STRIKETHROUGH))
                .append(Component.text(" GitForMinecraft ", NamedTextColor.GOLD))
                .append(Component.text("── ", NamedTextColor.DARK_GRAY, TextDecoration.STRIKETHROUGH))
                .append(Component.newline())
                .append(Component.text("推送自:", NamedTextColor.GRAY))
                .append(Component.text(author, NamedTextColor.YELLOW))
                .build();

        List<Component> lines = new ArrayList<>();

        for (String msg : commitMessages) {
            lines.add(Component.text("  \uD83D\uDCDD ", NamedTextColor.WHITE)
                    .append(Component.text(msg, NamedTextColor.WHITE)));
        }

        for (String file : added) {
            lines.add(Component.text("  + ", NamedTextColor.GREEN).append(Component.text(file, NamedTextColor.GREEN)));
        }
        for (String file : modified) {
            lines.add(Component.text("  ~ ", NamedTextColor.AQUA).append(Component.text(file, NamedTextColor.AQUA)));
        }
        for (String file : removed) {
            lines.add(Component.text("  - ", NamedTextColor.RED).append(Component.text(file, NamedTextColor.RED)));
        }

        Component footer = Component.text("────────────────────────────────", NamedTextColor.DARK_GRAY, TextDecoration.STRIKETHROUGH);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("gitforminecraft.notify.receive")) continue;

            player.sendMessage(header);
            for (Component line : lines) {
                player.sendMessage(line);
            }
            player.sendMessage(footer);
        }
    }
}

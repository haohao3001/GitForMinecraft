package top.haohao3001.gfm;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.haohao3001.gfm.command.GitCommand;
import top.haohao3001.gfm.webhook.WebhookHandler;
import top.haohao3001.gfm.webhook.WebhookServer;

import java.io.*;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;

public final class GitForMinecraft extends JavaPlugin {

    private WebhookServer webhookServer;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();

        initWebServer(config);
        initServerCommand();
    }

    @Override
    public void onDisable() {
        if (webhookServer != null) {
            webhookServer.stop();
            webhookServer = null;
        }
    }

    private void saveDefaultScript() {
        File scriptsDir = new File(getDataFolder(), "scripts");
        File exampleScript = new File(scriptsDir, "auto-pull.sh");
        if (!scriptsDir.mkdirs()&&exampleScript.exists()) return;

        try (InputStream in = getResource("example_script.sh")) {
            if (in != null) {
                Files.copy(in, exampleScript.toPath());
            } else {
                // Write a default example if resource doesn't exist
                Files.write(exampleScript.toPath(), List.of(
                        "# Example GitForMinecraft script",
                        "# Lines starting with '! ' execute system commands",
                        "# Other lines are dispatched as Bukkit console commands",
                        "",
                        "say Script executed from GitForMinecraft!"
                ));
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to save example script", e);
        }
    }

    private void initWebServer(FileConfiguration config){
        // Validate required config
        int port = config.getInt("webhook.port", 0);
        String path = config.getString("webhook.path", "gfm");
        String branch = config.getString("webhook.branch", "main");

        if (port == 0) {
            getLogger().log(Level.WARNING, "Webhook port is not configured (set webhook.port in config.yml)");
            getLogger().log(Level.WARNING, "GitForMinecraft will not start the webhook server.");
            return;
        }

        // Load default script example
        saveDefaultScript();

        // Start webhook server
        try {
            WebhookHandler handler = new WebhookHandler(this, branch);
            webhookServer = new WebhookServer(port, path, handler);
            webhookServer.start();
            getLogger().log(Level.INFO, "GitForMinecraft enabled. Webhook: http://0.0.0.0:" + port + "/" + path);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to start webhook server on port " + port, e);
        }
    }

    private void initServerCommand(){
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            GitCommand.register(this,commands.registrar());
        });
    }
}

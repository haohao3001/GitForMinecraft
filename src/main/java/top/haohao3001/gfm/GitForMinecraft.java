package top.haohao3001.gfm;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import top.haohao3001.gfm.webhook.WebhookHandler;
import top.haohao3001.gfm.webhook.WebhookServer;

import java.io.*;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;

public final class GitForMinecraft extends JavaPlugin {

    private WebhookServer webhookServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        FileConfiguration config = getConfig();

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

    @Override
    public void onDisable() {
        if (webhookServer != null) {
            webhookServer.stop();
            webhookServer = null;
        }
    }

    /**
     * Run a script from the scripts folder (similar to MineCICD's Script.run()).
     */
    public void runScript(String scriptName) throws Exception {
        File scriptsFolder = new File(getDataFolder(), "scripts");
        File scriptFile = new File(scriptsFolder, scriptName + ".sh");
        if (!scriptFile.exists()) {
            throw new FileNotFoundException("Script not found: " + scriptName + ".sh");
        }

        List<String> lines = Files.readAllLines(scriptFile.toPath());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("! ")) {
                // System command
                ProcessBuilder pb = new ProcessBuilder(line.substring(2).split(" "));
                pb.inheritIO();
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    getLogger().log(Level.WARNING, "Script '" + scriptName + "' line " + (i + 1)
                            + " exited with code " + exitCode);
                }
            } else {
                // Bukkit console command
                Bukkit.getScheduler().runTask(this, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
            }
        }
    }

    private void saveDefaultScript() {
        File scriptsDir = new File(getDataFolder(), "scripts");
        File exampleScript = new File(scriptsDir, "auto-pull.sh");
        if (exampleScript.exists()) return;
        scriptsDir.mkdirs();

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
}

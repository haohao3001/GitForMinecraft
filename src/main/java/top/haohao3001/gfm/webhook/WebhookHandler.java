package top.haohao3001.gfm.webhook;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import top.haohao3001.gfm.GitForMinecraft;
import top.haohao3001.gfm.executor.ChangeNotifier;

import java.io.*;
import java.util.*;
import java.util.logging.Level;

/**
 * Handles incoming GitHub webhook POST requests.
 * Extracted from MineCICD's WebhookHandler - no git operations, no secrets.
 */
public class WebhookHandler implements HttpHandler {

    private static final int MAX_BODY_SIZE = 512000;

    private final Gson gson = new Gson();
    private final GitForMinecraft plugin;
    private final String expectedBranch;

    public WebhookHandler(GitForMinecraft plugin, String expectedBranch) {
        this.plugin = plugin;
        this.expectedBranch = expectedBranch;
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            // Read request body (same approach as MineCICD)
            StringBuilder bodyBuilder = new StringBuilder();
            InputStream ios = exchange.getRequestBody();
            int remaining = MAX_BODY_SIZE;
            int b;
            while ((b = ios.read()) != -1 && remaining-- > 0) {
                bodyBuilder.append((char) b);
            }

            if (remaining <= 0) {
                exchange.sendResponseHeaders(400, 0);
                plugin.getLogger().log(Level.WARNING, "Webhook body exceeded maximum size of " + MAX_BODY_SIZE + " bytes");
                return;
            }

            // Respond immediately to avoid GitHub timeout
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();

            // Parse push event
            PushEvent event = gson.fromJson(bodyBuilder.toString(), PushEvent.class);
            if (event == null || event.getRef() == null) {
                plugin.getLogger().log(Level.WARNING, "Received invalid webhook payload");
                return;
            }

            // Validate branch
            String branch = event.getBranch();
            if (!branch.equals(expectedBranch)) {
                plugin.getLogger().log(Level.INFO,
                        "Received webhook for branch '" + branch + "' but expected '" + expectedBranch + "', ignoring");
                return;
            }

            plugin.getLogger().log(Level.INFO, "Received valid webhook push to '" + branch + "'");

            // Process asynchronously on Bukkit scheduler
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> processEvent(event));

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to process webhook request", e);
            try {
                exchange.sendResponseHeaders(500, 0);
            } catch (IOException ignored) {
            }
        }
    }

    private void processEvent(PushEvent event) {
        // Collect all file changes across all commits
        Set<String> addedFiles = new LinkedHashSet<>();
        Set<String> modifiedFiles = new LinkedHashSet<>();
        Set<String> removedFiles = new LinkedHashSet<>();

        // Parse CICD commands from all commit messages
        List<String> cicdCommands = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        boolean cicdReload = false;
        boolean cicdRestart = false;

        String authorName = "Unknown";
        String timestamp = "";

        List<PushEvent.Commit> commits = event.getCommits();
        PushEvent.Commit headCommit = event.getHeadCommit();

        if (commits != null) {
            for (PushEvent.Commit commit : commits) {
                // Collect file changes
                if (commit.getAdded() != null) addedFiles.addAll(commit.getAdded());
                if (commit.getModified() != null) modifiedFiles.addAll(commit.getModified());
                if (commit.getRemoved() != null) removedFiles.addAll(commit.getRemoved());

                // Parse CICD commands from commit message
                parseCicdCommands(commit.getMessage(), cicdCommands, scripts);

                // Track author from latest commit
                if (commit.getAuthor() != null) {
                    authorName = commit.getAuthor().getName();
                }
                if (commit.getTimestamp() != null) {
                    timestamp = commit.getTimestamp();
                }
            }
        }

        // Fallback to head_commit if no commits array
        if (commits == null || commits.isEmpty()) {
            if (headCommit != null) {
                if (headCommit.getAdded() != null) addedFiles.addAll(headCommit.getAdded());
                if (headCommit.getModified() != null) modifiedFiles.addAll(headCommit.getModified());
                if (headCommit.getRemoved() != null) removedFiles.addAll(headCommit.getRemoved());
                parseCicdCommands(headCommit.getMessage(), cicdCommands, scripts);
                if (headCommit.getAuthor() != null) authorName = headCommit.getAuthor().getName();
                if (headCommit.getTimestamp() != null) timestamp = headCommit.getTimestamp();
            }
        }

        plugin.getLogger().log(Level.INFO, "Processing push by " + authorName
                + " - " + addedFiles.size() + " added, "
                + modifiedFiles.size() + " modified, "
                + removedFiles.size() + " removed");

        // Notify online players of changes
        ChangeNotifier.notifyChanges(authorName, addedFiles, modifiedFiles, removedFiles);

        // Execute hook rules based on changed files
        if (plugin.getConfig().getBoolean("file-hooks.enabled", true)) {
            Set<String> allChangedFiles = new LinkedHashSet<>();
            allChangedFiles.addAll(addedFiles);
            allChangedFiles.addAll(modifiedFiles);
            allChangedFiles.addAll(removedFiles);

            executeFileHooks(allChangedFiles);
        }

        // Execute CICD run commands
        if (plugin.getConfig().getBoolean("cicd-commands.enabled", true)) {
            for (String cmd : cicdCommands) {
                plugin.getLogger().log(Level.INFO, "Executing CICD run: " + cmd);
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            }
        }

        // Execute CICD script commands
        if (plugin.getConfig().getBoolean("cicd-commands.allow-scripts", false)) {
            for (String script : scripts) {
                plugin.getLogger().log(Level.INFO, "Executing CICD script: " + script);
                try {
                    plugin.runScript(script);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to run script '" + script + "'", e);
                }
            }
        }

        // Handle CICD reload (global reload)
        if (cicdReload) {
            plugin.getLogger().log(Level.INFO, "CICD global-reload triggered");
            Bukkit.getScheduler().runTask(plugin, Bukkit::reload);
        }

        // Handle CICD restart
        if (cicdRestart) {
            plugin.getLogger().log(Level.INFO, "CICD restart triggered");
            Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
        }
    }

    /**
     * Parse CICD commands from a commit message.
     * Format: lines starting with "CICD" followed by a command.
     */
    private void parseCicdCommands(String message, List<String> commands, List<String> scripts) {
        if (message == null || message.isEmpty()) return;

        String[] lines = message.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("CICD")) continue;

            String command = trimmed.substring(4).trim();
            if (command.startsWith("run ")) {
                commands.add(command.substring(4).trim());
            } else if (command.startsWith("script ")) {
                if (plugin.getConfig().getBoolean("cicd-commands.allow-scripts", false)) {
                    scripts.add(command.substring(7).trim());
                }
            } else if (command.equals("global-reload")) {
                // Handled by the caller
            } else if (command.equals("restart")) {
                // Handled by the caller
            } else {
                plugin.getLogger().log(Level.WARNING, "Unknown CICD command: " + command);
            }
        }
    }

    /**
     * Match changed files against configured hook rules and execute matching commands.
     */
    private void executeFileHooks(Set<String> changedFiles) {
        List<Map<?, ?>> hookList = plugin.getConfig().getMapList("file-hooks.rules");
        if (hookList == null || hookList.isEmpty()) return;

        for (Map<?, ?> rule : hookList) {
            String pattern = (String) rule.get("pattern");
            String onChanged = (String) rule.get("on-change");
            if (pattern == null || onChanged == null) continue;

            for (String file : changedFiles) {
                if (pathMatches(file, pattern)) {
                    String resolved = resolvePlaceholders(onChanged, file, pattern);
                    plugin.getLogger().log(Level.INFO, "Hook triggered: [" + pattern + "] matched " + file + " → " + resolved);
                    Bukkit.getScheduler().runTask(plugin, () ->
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved));
                }
            }
        }
    }

    /**
     * Simple glob matching: * matches any chars except /, ** matches any chars including /.
     */
    private boolean pathMatches(String path, String pattern) {
        // Convert pattern to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("?", ".")
                .replace("**", "#DOUBLESTAR#")
                .replace("*", "[^/]*")
                .replace("#DOUBLESTAR#", ".*");
        return path.matches(regex);
    }

    /**
     * Resolve placeholders in hook command template:
     * {file} - full file path
     * {parent} - immediate parent directory name
     * {dir1}, {dir2}, etc. - path segments (dir1=first, dir2=second, ...)
     */
    private String resolvePlaceholders(String template, String filePath, String pattern) {
        String result = template.replace("{file}", filePath);

        // Extract parent directory
        int lastSep = filePath.lastIndexOf('/');
        String parent = lastSep > 0 ? filePath.substring(0, lastSep) : "";
        result = result.replace("{parent}", parent);

        // Extract individual path segments
        String[] segments = filePath.split("/");
        for (int i = 0; i < segments.length; i++) {
            result = result.replace("{dir" + (i + 1) + "}", segments[i]);
        }

        return result;
    }
}

package top.haohao3001.gfm.webhook;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import top.haohao3001.gfm.GitForMinecraft;
import top.haohao3001.gfm.executor.ChangeNotifier;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
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
    private final ExecutorService executor;

    public WebhookHandler(GitForMinecraft plugin, String expectedBranch, ExecutorService executor) {
        this.plugin = plugin;
        this.expectedBranch = expectedBranch;
        this.executor = executor;
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            // Read request body with proper UTF-8 decoding
            StringBuilder bodyBuilder = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8);
            char[] buf = new char[4096];
            int totalChars = 0;
            int charsRead;
            while ((charsRead = reader.read(buf, 0, Math.min(buf.length, MAX_BODY_SIZE - totalChars))) != -1 && totalChars < MAX_BODY_SIZE) {
                bodyBuilder.append(buf, 0, charsRead);
                totalChars += charsRead;
            }

            if (totalChars >= MAX_BODY_SIZE) {
                String msg = "Webhook body exceeded maximum size of " + MAX_BODY_SIZE + " bytes";
                plugin.getLogger().log(Level.WARNING,msg);
                sendResponseAndCloseConnect(exchange,400,msg);
                return;
            }

            // Parse push event
            PushEvent event = gson.fromJson(bodyBuilder.toString(), PushEvent.class);
            if (event == null || event.getRef() == null) {
                String msg = "Received invalid webhook payload";
                plugin.getLogger().log(Level.WARNING, msg);
                sendResponseAndCloseConnect(exchange,400,msg);
                return;
            }

            // Validate branch
            String branch = event.getBranch();
            if (!branch.equals(expectedBranch)) {
                String msg = "Received webhook for branch '" + branch + "' but expected '" + expectedBranch + "', ignoring";
                plugin.getLogger().log(Level.INFO, msg);
                sendResponseAndCloseConnect(exchange,400,msg);
                return;
            }

            plugin.getLogger().log(Level.INFO, "Received valid webhook push to '" + branch + "'");

            // Process on the dedicated script thread
            executor.submit(() -> processEvent(event));

            sendResponseAndCloseConnect(exchange,200,"200 OK");

        } catch (Exception e) {
            String msg = "Failed to process webhook request";
            plugin.getLogger().log(Level.SEVERE, msg, e);
            try {
                sendResponseAndCloseConnect(exchange,500,msg);
            } catch (IOException ignored) {
            }
        }
    }

    public void sendResponseAndCloseConnect(HttpExchange exchange,int responseCode,String responseMessage) throws IOException {
        exchange.sendResponseHeaders(responseCode, responseMessage.length());
        OutputStream os = exchange.getResponseBody();
        os.write(responseMessage.getBytes());
        os.close();
    }

    private void processEvent(PushEvent event) {
        // Collect all file changes across all commits
        Set<String> addedFiles = new LinkedHashSet<>();
        Set<String> modifiedFiles = new LinkedHashSet<>();
        Set<String> removedFiles = new LinkedHashSet<>();

        // Parse CICD commands from all commit messages
        CicdParseResult cicdResult = new CicdParseResult(new ArrayList<>(), new ArrayList<>(), false, false);

        String authorName = "Unknown";
        List<String> commitMessages = new ArrayList<>();

        List<PushEvent.Commit> commits = event.getCommits();
        PushEvent.Commit headCommit = event.getHeadCommit();

        if (commits != null) {
            for (PushEvent.Commit commit : commits) {
                // Collect file changes
                if (commit.getAdded() != null) addedFiles.addAll(commit.getAdded());
                if (commit.getModified() != null) modifiedFiles.addAll(commit.getModified());
                if (commit.getRemoved() != null) removedFiles.addAll(commit.getRemoved());

                // Parse CICD commands from commit message and merge
                if (commit.getMessage() != null) {
                    cicdResult = cicdResult.merge(parseCicdCommands(commit.getMessage()));
                    String firstLine = commit.getMessage().split("\n", 2)[0];
                    if (!firstLine.isBlank()) commitMessages.add(firstLine);
                }

                // Track author from latest commit
                if (commit.getAuthor() != null) {
                    authorName = commit.getAuthor().getName();
                }
            }
        }

        // Fallback to head_commit if no commits array
        if (commits == null || commits.isEmpty()) {
            if (headCommit != null) {
                if (headCommit.getAdded() != null) addedFiles.addAll(headCommit.getAdded());
                if (headCommit.getModified() != null) modifiedFiles.addAll(headCommit.getModified());
                if (headCommit.getRemoved() != null) removedFiles.addAll(headCommit.getRemoved());
                if (headCommit.getMessage() != null) {
                    cicdResult = cicdResult.merge(parseCicdCommands(headCommit.getMessage()));
                    String firstLine = headCommit.getMessage().split("\n", 2)[0];
                    if (!firstLine.isBlank()) commitMessages.add(firstLine);
                }
                if (headCommit.getAuthor() != null) authorName = headCommit.getAuthor().getName();
            }
        }

        plugin.getLogger().log(Level.INFO, "Processing push by " + authorName
                + " - " + addedFiles.size() + " added, "
                + modifiedFiles.size() + " modified, "
                + removedFiles.size() + " removed");

        // Notify online players of changes
        ChangeNotifier.notifyChanges(authorName, addedFiles, modifiedFiles, removedFiles, commitMessages);

        // Auto-pull: run scripts/auto-pull.sh if configured
        if (plugin.getConfig().getBoolean("webhook.auto-pull", false)) {
            plugin.getLogger().log(Level.INFO, "Auto-pull enabled, executing auto-pull.sh...");
            try {
                runScript("auto-pull");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "auto-pull.sh failed", e);
            }
        }

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
            for (String cmd : cicdResult.commands()) {
                plugin.getLogger().log(Level.INFO, "Executing CICD run: " + cmd);
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            }
        }

        // Execute CICD script commands
        if (plugin.getConfig().getBoolean("cicd-commands.allow-scripts", false)) {
            for (String script : cicdResult.scripts()) {
                plugin.getLogger().log(Level.INFO, "Executing CICD script: " + script);
                try {
                    runScript(script);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to run script '" + script + "'", e);
                }
            }
        }

        // Handle CICD reload (global reload)
        if (cicdResult.reload()) {
            plugin.getLogger().log(Level.INFO, "CICD global-reload triggered");
            Bukkit.getScheduler().runTask(plugin, Bukkit::reload);
        }

        // Handle CICD restart
        if (cicdResult.restart()) {
            plugin.getLogger().log(Level.INFO, "CICD restart triggered");
            Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
        }
    }

    /**
     * Parse CICD commands from a commit message.
     * Format: lines starting with "CICD" followed by a command.
     *
     * @return CicdParseResult containing extracted commands, scripts, and flags
     */
    private CicdParseResult parseCicdCommands(String message) {
        List<String> commands = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        boolean reload = false;
        boolean restart = false;

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
                reload = true;
            } else if (command.equals("restart")) {
                restart = true;
            } else {
                plugin.getLogger().log(Level.WARNING, "Unknown CICD command: " + command);
            }
        }

        return new CicdParseResult(commands, scripts, reload, restart);
    }

    /**
     * Match changed files against configured hook rules and execute matching commands.
     */
    private void executeFileHooks(Set<String> changedFiles) {
        List<Map<?, ?>> hookList = plugin.getConfig().getMapList("file-hooks.rules");
        if (hookList.isEmpty()) return;

        for (Map<?, ?> rule : hookList) {
            String pattern = (String) rule.get("pattern");
            String onChanged = (String) rule.get("on-change");
            if (pattern == null || onChanged == null) continue;

            boolean firstMatched = false;
            for (String file : changedFiles) {
                if (!firstMatched&&pathMatches(file, pattern)) {
                    firstMatched=true;
                    plugin.getLogger().log(Level.INFO, "Hook triggered: [" + pattern + "] matched " + file);
                    try {
                        runScript(onChanged);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE,"Fail to run script:" + onChanged,e);
                    }
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

    /**
     * Run a script from the scripts folder (similar to MineCICD's Script.run()).
     */
    public void runScript(String scriptName) throws Exception {
        File scriptsFolder = new File(plugin.getDataFolder(), "scripts");
        File scriptFile = new File(scriptsFolder, scriptName + ".sh");
        if (!scriptFile.exists()) {
            throw new FileNotFoundException("Script not found: " + scriptName + ".sh");
        }

        List<String> lines = Files.readAllLines(scriptFile.toPath());

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("! ")) {
                // System command with output capture
                ProcessBuilder pb = new ProcessBuilder(line.substring(2).split(" "));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String outputLine;
                    while ((outputLine = reader.readLine()) != null) {
                        plugin.getLogger().log(Level.INFO, "[script:" + scriptName + "] " + outputLine);
                    }
                }
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    plugin.getLogger().log(Level.WARNING, "Script '" + scriptName + "' line " + (i + 1)
                            + " exited with code " + exitCode);
                }
            } else {
                if(Bukkit.isPrimaryThread()){
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                }else {
                    Bukkit.getScheduler().callSyncMethod(plugin,()->{
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                        return null;
                    }).get();
                }
            }
        }
    }
}

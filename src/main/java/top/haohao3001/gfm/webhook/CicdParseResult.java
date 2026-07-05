package top.haohao3001.gfm.webhook;

import java.util.List;

/**
 * Result of parsing CICD commands from a commit message.
 */
public record CicdParseResult(
        List<String> commands,
        List<String> scripts,
        boolean reload,
        boolean restart
) {
    /**
     * Merge this result with another, combining all commands and scripts,
     * and or-ing the reload/restart flags.
     */
    public CicdParseResult merge(CicdParseResult other) {
        commands.addAll(other.commands);
        scripts.addAll(other.scripts);
        return new CicdParseResult(commands, scripts, reload || other.reload, restart || other.restart);
    }
}

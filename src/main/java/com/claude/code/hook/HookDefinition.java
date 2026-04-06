package com.claude.code.hook;

/**
 * A single hook definition loaded from configuration.
 *
 * Matches Claude Code's hook format:
 * - event: the event name (e.g. "PreToolUse")
 * - matcher: regex to match tool name (e.g. "Bash" or "Edit|Write")
 * - type: hook type ("command")
 * - command: shell command to execute
 * - timeout: max execution time in seconds (default 30)
 */
public class HookDefinition {
    private final String event;
    private final String matcher;
    private final String type;
    private final String command;
    private final int timeout;

    public HookDefinition(String event, String matcher, String type, String command, int timeout) {
        this.event = event;
        this.matcher = matcher != null ? matcher : ".*";
        this.type = type != null ? type : "command";
        this.command = command;
        this.timeout = timeout > 0 ? timeout : 30;
    }

    /** Check if this hook matches a given tool name */
    public boolean matchesTool(String toolName) {
        if (toolName == null) return false;
        return toolName.matches(matcher);
    }

    public String getEvent() { return event; }
    public String getMatcher() { return matcher; }
    public String getType() { return type; }
    public String getCommand() { return command; }
    public int getTimeout() { return timeout; }

    @Override
    public String toString() {
        return "HookDef[%s/%s -> %s (%ds)]".formatted(event, matcher, type, timeout);
    }
}

package com.claude.code.skill;

import java.util.List;

/**
 * Represents a loaded skill with parsed frontmatter and content.
 *
 * SKILL.md format (Claude Code compatible):
 * ---
 * name: skill-name
 * description: What this skill does and when to use it
 * scope: builtin | user | project
 * disable-model-invocation: true | false
 * user-invocable: true | false
 * allowed-tools: Read Grep Glob
 * paths: src/ts, lib/rs (glob patterns)
 * ---
 *
 * Invocation control (from Claude Code docs):
 * - (default): Both user and Claude can invoke. Description always in system prompt.
 * - disable-model-invocation: true: Only user can invoke. Description NOT in system prompt.
 * - user-invocable: false: Only Claude can invoke. Description always in system prompt.
 */
public class Skill {
    private final String name;
    private final String description;
    private final String scope;
    private final boolean disableModelInvocation;
    private final boolean userInvocable;
    private final List<String> allowedTools;
    private final List<String> paths;
    private final String content;
    private final String sourcePath;

    public Skill(String name, String description, String scope,
                 boolean disableModelInvocation, boolean userInvocable,
                 List<String> allowedTools, List<String> paths,
                 String content, String sourcePath) {
        this.name = name;
        this.description = description;
        this.scope = scope != null ? scope : "project";
        this.disableModelInvocation = disableModelInvocation;
        this.userInvocable = userInvocable;
        this.allowedTools = allowedTools != null ? allowedTools : List.of();
        this.paths = paths != null ? paths : List.of();
        this.content = content;
        this.sourcePath = sourcePath;
    }

    /** Whether Claude (the model) can auto-invoke this skill */
    public boolean isModelInvocable() {
        return !disableModelInvocation;
    }

    /** Whether the description should appear in the system prompt for Claude to see */
    public boolean isVisibleToModel() {
        return isModelInvocable();
    }

    /** Whether the user can invoke this skill via /command */
    public boolean isUserInvocable() {
        return userInvocable;
    }

    /** Slash command name */
    public String getCommandName() {
        return "/" + name;
    }

    /** Check if a user message is a slash command targeting this skill */
    public boolean matchesCommand(String userMessage) {
        if (userMessage == null || !userInvocable) return false;
        String trimmed = userMessage.trim();
        if (trimmed.equals(getCommandName())) return true;
        if (trimmed.startsWith(getCommandName() + " ")) return true;
        return false;
    }

    /** Extract the argument portion after a slash command */
    public String extractArgument(String userMessage) {
        if (userMessage == null) return "";
        String trimmed = userMessage.trim();
        String prefix = getCommandName() + " ";
        if (trimmed.startsWith(prefix)) {
            return trimmed.substring(prefix.length()).trim();
        }
        return "";
    }

    /**
     * Check if this skill is relevant to a given file path.
     * Used for path-based auto-activation (Claude Code's `paths` field).
     */
    public boolean matchesPath(String filePath) {
        if (paths.isEmpty() || filePath == null) return false;
        String normalized = filePath.replace("\\", "/");
        for (var pattern : paths) {
            String p = pattern.replace("\\", "/");
            // Simple glob: **/*.ts, src/**/*.java, etc.
            if (matchGlob(normalized, p)) return true;
        }
        return false;
    }

    private boolean matchGlob(String path, String pattern) {
        // Convert simple glob to regex
        String regex = pattern
                .replace(".", "\\.")
                .replace("**/", "(.*/)?")
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("?", "[^/]");
        return path.matches(regex) || path.matches(regex + ".*");
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getScope() { return scope; }
    public List<String> getAllowedTools() { return allowedTools; }
    public List<String> getPaths() { return paths; }
    public String getContent() { return content; }
    public String getSourcePath() { return sourcePath; }

    @Override
    public String toString() {
        return "Skill[name=%s, scope=%s, modelInvocable=%s, userInvocable=%s, desc=%s]"
                .formatted(name, scope, isModelInvocable(), userInvocable,
                        description != null && description.length() > 40
                                ? description.substring(0, 40) + "..." : description);
    }
}

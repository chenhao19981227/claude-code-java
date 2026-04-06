package com.claude.code.permission;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

public class PermissionChecker {
    private static final String[] DANGEROUS_FILES = {
        ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile", ".bash_logout",
        ".zshrc", ".zprofile", ".profile", ".ripgreprc", ".mcp.json", ".claude.json",
        ".env", ".env.local"
    };
    private static final String[] DANGEROUS_DIRS = {".git", ".vscode", ".idea", ".claude"};

    private final List<PermissionRule> allowRules = new ArrayList<>();
    private final List<PermissionRule> denyRules = new ArrayList<>();
    private final List<PermissionRule> askRules = new ArrayList<>();
    private PermissionMode mode = PermissionMode.DEFAULT;
    private final String workingDirectory;
    private String agentMode = "build"; // "plan" or "build"
    private final Set<String> allowedAllTools = new HashSet<>();

    public PermissionChecker(String workingDirectory) {
        this.workingDirectory = workingDirectory != null ? workingDirectory : System.getProperty("user.dir");
    }

    public void setMode(PermissionMode mode) { this.mode = mode; }
    public PermissionMode getMode() { return mode; }

    public void setAgentMode(String agentMode) { this.agentMode = agentMode; }
    public String getAgentMode() { return agentMode; }

    public void addAllowRule(PermissionRule rule) { allowRules.add(rule); }
    public void addDenyRule(PermissionRule rule) { denyRules.add(rule); }
    public void addAskRule(PermissionRule rule) { askRules.add(rule); }

    public void allowAllForTool(String toolName) { allowedAllTools.add(toolName); }
    public boolean isAllowedAll(String toolName) { return allowedAllTools.contains(toolName); }

    public void resetAllowedAll() { allowedAllTools.clear(); }

    public PermissionResult checkPermission(String toolName, String input, String filePath) {
        // If user previously chose "Allow All" for this tool, auto-approve
        if (allowedAllTools.contains(toolName)) {
            return PermissionResult.allow(input, PermissionDecisionReason.mode("allow_all"));
        }

        // Plan mode: deny all non-read tools
        if ("plan".equals(agentMode) && !isReadOnlyTool(toolName)) {
            return PermissionResult.deny(
                "Tool '" + toolName + "' is not allowed in Plan mode. Switch to Build mode to use write tools.",
                PermissionDecisionReason.mode("plan"));
        }

        // Build mode: bypass rules still work
        if (mode.isBypass()) {
            return PermissionResult.allow(input, PermissionDecisionReason.mode("bypassPermissions"));
        }

        // Check deny rules
        for (var rule : denyRules) {
            if (rule.matchesTool(toolName) && matchesContent(rule, input, filePath)) {
                return PermissionResult.deny(
                    "Tool '" + toolName + "' is denied by rule",
                    PermissionDecisionReason.rule(rule.getRuleContent()));
            }
        }

        // Check ask rules
        for (var rule : askRules) {
            if (rule.matchesTool(toolName) && matchesContent(rule, input, filePath)) {
                return PermissionResult.ask(
                    "Tool '" + toolName + "' requires approval: " + rule.getRuleContent(),
                    PermissionDecisionReason.rule(rule.getRuleContent()));
            }
        }

        // Path safety check for non-read tools
        if (filePath != null && !isReadOnlyTool(toolName)) {
            PermissionResult safetyCheck = checkPathSafety(filePath);
            if (safetyCheck != null) return safetyCheck;
        }

        // Check allow rules
        for (var rule : allowRules) {
            if (rule.matchesTool(toolName) && matchesContent(rule, input, filePath)) {
                return PermissionResult.allow(input, PermissionDecisionReason.rule(rule.getRuleContent()));
            }
        }

        // Default behavior based on mode
        return switch (mode) {
            case ACCEPT_EDITS -> PermissionResult.allow(input, PermissionDecisionReason.mode("acceptEdits"));
            case PLAN -> {
                if (isReadOnlyTool(toolName)) yield PermissionResult.allow(input, PermissionDecisionReason.mode("plan"));
                yield PermissionResult.deny("Tool '" + toolName + "' is not allowed in plan mode", PermissionDecisionReason.mode("plan"));
            }
            case DONT_ASK -> PermissionResult.deny("Tool '" + toolName + "' not explicitly allowed", PermissionDecisionReason.mode("dontAsk"));
            case AUTO -> {
                if (isReadOnlyTool(toolName)) yield PermissionResult.allow(input, PermissionDecisionReason.mode("auto"));
                yield PermissionResult.ask("Allow " + toolName + "?", PermissionDecisionReason.mode("auto"));
            }
            default -> PermissionResult.ask("Allow " + toolName + "?", PermissionDecisionReason.mode("default"));
        };
    }

    private boolean matchesContent(PermissionRule rule, String input, String filePath) {
        if (rule.getRuleContent() == null || rule.getRuleContent().isEmpty()) return true;
        String pattern = rule.getRuleContent().replace(".", "\\.").replace("*", ".*");
        if (filePath != null) {
            if (Pattern.matches(pattern, filePath)) return true;
            if (Pattern.matches(pattern, new File(filePath).getName())) return true;
        }
        if (input != null && Pattern.matches(pattern, input)) return true;
        return false;
    }

    private PermissionResult checkPathSafety(String path) {
        String normalized = new File(path).getAbsolutePath().replace("\\", "/");
        String[] parts = normalized.split("/");

        for (var dangerousDir : DANGEROUS_DIRS) {
            for (var part : parts) {
                if (part.equals(dangerousDir)) {
                    return PermissionResult.ask(
                        "Path contains '" + dangerousDir + "' which is a protected directory",
                        PermissionDecisionReason.safetyCheck("protected_directory"));
                }
            }
        }

        String fileName = parts[parts.length - 1];
        for (var dangerousFile : DANGEROUS_FILES) {
            if (fileName.equals(dangerousFile)) {
                return PermissionResult.ask(
                    "File '" + dangerousFile + "' is protected",
                    PermissionDecisionReason.safetyCheck("protected_file"));
            }
        }
        return null;
    }

    /**
     * Check if a tool is read-only. Uses the TOOL NAME (not class name).
     * Read-only tools: Read, Glob, Grep, TodoWrite
     */
    private boolean isReadOnlyTool(String toolName) {
        if (toolName == null) return false;
        return switch (toolName) {
            case "Read", "Glob", "Grep", "TodoWrite" -> true;
            default -> false;
        };
    }

    public boolean isInWorkingDirectory(String path) {
        if (path == null) return false;
        String abs = new File(path).getAbsolutePath().replace("\\", "/");
        String wd = new File(workingDirectory).getAbsolutePath().replace("\\", "/");
        return abs.startsWith(wd);
    }
}

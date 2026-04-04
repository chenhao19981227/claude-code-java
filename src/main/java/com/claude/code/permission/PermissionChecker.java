package com.claude.code.permission;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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

    public PermissionChecker(String workingDirectory) {
        this.workingDirectory = workingDirectory != null ? workingDirectory : System.getProperty("user.dir");
    }

    public void setMode(PermissionMode mode) { this.mode = mode; }
    public PermissionMode getMode() { return mode; }

    public void addAllowRule(PermissionRule rule) { allowRules.add(rule); }
    public void addDenyRule(PermissionRule rule) { denyRules.add(rule); }
    public void addAskRule(PermissionRule rule) { askRules.add(rule); }

    public PermissionResult checkPermission(String toolName, String input, String filePath) {
        // 1. Bypass mode - allow everything
        if (mode.isBypass()) {
            return PermissionResult.allow(input, PermissionDecisionReason.mode("bypassPermissions"));
        }

        // 2. Check deny rules
        for (PermissionRule rule : denyRules) {
            if (rule.matchesTool(toolName) && matchesContent(rule, input, filePath)) {
                return PermissionResult.deny(
                    "Tool '" + toolName + "' is denied by rule",
                    PermissionDecisionReason.rule(rule.getRuleContent())
                );
            }
        }

        // 3. Check ask rules
        for (PermissionRule rule : askRules) {
            if (rule.matchesTool(toolName) && matchesContent(rule, input, filePath)) {
                return PermissionResult.ask(
                    "Tool '" + toolName + "' requires approval: " + rule.getRuleContent(),
                    PermissionDecisionReason.rule(rule.getRuleContent())
                );
            }
        }

        // 4. Check dangerous paths for write operations
        if (filePath != null && !isReadOnlyTool(toolName)) {
            PermissionResult safetyCheck = checkPathSafety(filePath);
            if (safetyCheck != null) return safetyCheck;
        }

        // 5. Check allow rules
        for (PermissionRule rule : allowRules) {
            if (rule.matchesTool(toolName) && matchesContent(rule, input, filePath)) {
                return PermissionResult.allow(input, PermissionDecisionReason.rule(rule.getRuleContent()));
            }
        }

        // 6. Mode-based default
        switch (mode) {
            case ACCEPT_EDITS:
                return PermissionResult.allow(input, PermissionDecisionReason.mode("acceptEdits"));
            case PLAN:
                if (isReadOnlyTool(toolName)) {
                    return PermissionResult.allow(input, PermissionDecisionReason.mode("plan"));
                }
                return PermissionResult.deny("Tool '" + toolName + "' is not allowed in plan mode", PermissionDecisionReason.mode("plan"));
            case DONT_ASK:
                return PermissionResult.deny("Tool '" + toolName + "' not explicitly allowed", PermissionDecisionReason.mode("dontAsk"));
            case AUTO:
                // In auto mode, default to ask for non-read-only tools
                if (isReadOnlyTool(toolName)) {
                    return PermissionResult.allow(input, PermissionDecisionReason.mode("auto"));
                }
                return PermissionResult.ask("Allow " + toolName + "?", PermissionDecisionReason.mode("auto"));
            default:
                return PermissionResult.ask("Allow " + toolName + "?", PermissionDecisionReason.mode("default"));
        }
    }

    private boolean matchesContent(PermissionRule rule, String input, String filePath) {
        if (rule.getRuleContent() == null || rule.getRuleContent().isEmpty()) return true;
        if (filePath != null) {
            String pattern = rule.getRuleContent().replace(".", "\\.").replace("*", ".*");
            if (Pattern.matches(pattern, filePath)) return true;
            if (Pattern.matches(pattern, new File(filePath).getName())) return true;
        }
        if (input != null) {
            String pattern = rule.getRuleContent().replace(".", "\\.").replace("*", ".*");
            if (Pattern.matches(pattern, input)) return true;
        }
        return false;
    }

    private PermissionResult checkPathSafety(String path) {
        String normalized = new File(path).getAbsolutePath().replace("\\", "/");
        String[] parts = normalized.split("/");

        for (String dangerousDir : DANGEROUS_DIRS) {
            for (String part : parts) {
                if (part.equals(dangerousDir)) {
                    return PermissionResult.ask(
                        "Path contains '" + dangerousDir + "' which is a protected directory",
                        PermissionDecisionReason.safetyCheck("protected_directory")
                    );
                }
            }
        }

        String fileName = parts[parts.length - 1];
        for (String dangerousFile : DANGEROUS_FILES) {
            if (fileName.equals(dangerousFile)) {
                return PermissionResult.ask(
                    "File '" + dangerousFile + "' is protected",
                    PermissionDecisionReason.safetyCheck("protected_file")
                );
            }
        }
        return null;
    }

    private boolean isReadOnlyTool(String toolName) {
        return "FileReadTool".equals(toolName) || "GlobTool".equals(toolName) || "GrepTool".equals(toolName);
    }

    public boolean isInWorkingDirectory(String path) {
        if (path == null) return false;
        String abs = new File(path).getAbsolutePath().replace("\\", "/");
        String wd = new File(workingDirectory).getAbsolutePath().replace("\\", "/");
        return abs.startsWith(wd);
    }
}

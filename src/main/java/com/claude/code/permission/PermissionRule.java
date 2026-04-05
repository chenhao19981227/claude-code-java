package com.claude.code.permission;

public class PermissionRule {
    public enum Source { USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS, FLAG_SETTINGS, POLICY_SETTINGS, CLI_ARG, COMMAND, SESSION }
    public enum Behavior { ALLOW, DENY, ASK }

    private final Source source;
    private final Behavior behavior;
    private final String toolName;
    private final String ruleContent;

    public PermissionRule(Source source, Behavior behavior, String toolName, String ruleContent) {
        this.source = source;
        this.behavior = behavior;
        this.toolName = toolName;
        this.ruleContent = ruleContent;
    }

    public Source getSource() { return source; }
    public Behavior getBehavior() { return behavior; }
    public String getToolName() { return toolName; }
    public String getRuleContent() { return ruleContent; }

    public boolean matchesTool(String name) {
        return toolName == null || toolName.isEmpty() || toolName.equals(name);
    }
}

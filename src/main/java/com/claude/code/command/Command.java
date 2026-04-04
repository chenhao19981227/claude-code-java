package com.claude.code.command;

import com.claude.code.tool.ToolUseContext;

public abstract class Command {
    public enum CommandType { PROMPT, LOCAL, LOCAL_UI }

    private final String name;
    private final String description;
    private final CommandType type;
    private final boolean enabled;
    private final boolean userInvocable;

    protected Command(String name, String description, CommandType type) {
        this(name, description, type, true, true);
    }

    protected Command(String name, String description, CommandType type, boolean enabled, boolean userInvocable) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.enabled = enabled;
        this.userInvocable = userInvocable;
    }

    public String getPromptForCommand(String args, ToolUseContext context) {
        throw new UnsupportedOperationException("Not a prompt command: " + name);
    }

    public LocalCommandResult executeLocal(String args, ToolUseContext context) {
        throw new UnsupportedOperationException("Not a local command: " + name);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public CommandType getType() { return type; }
    public boolean isEnabled() { return enabled; }
    public boolean isUserInvocable() { return userInvocable; }
}

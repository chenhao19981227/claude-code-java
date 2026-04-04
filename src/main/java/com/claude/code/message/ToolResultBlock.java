package com.claude.code.message;

public class ToolResultBlock {
    private final String toolUseId;
    private final String content;
    private final boolean isError;

    public ToolResultBlock(String toolUseId, String content, boolean isError) {
        this.toolUseId = toolUseId;
        this.content = content;
        this.isError = isError;
    }

    public String getToolUseId() { return toolUseId; }
    public String getContent() { return content; }
    public boolean isError() { return isError; }

    public static ToolResultBlock success(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, false);
    }

    public static ToolResultBlock error(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, true);
    }
}

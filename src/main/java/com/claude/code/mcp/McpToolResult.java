package com.claude.code.mcp;

public class McpToolResult {
    private final String content;
    private final boolean isError;

    public McpToolResult(String content, boolean isError) {
        this.content = content;
        this.isError = isError;
    }

    public String getContent() { return content; }
    public boolean isError() { return isError; }
}

package com.claude.code.message;

public class ToolUseBlock {
    private final String id;
    private final String toolName;
    private String inputJson;

    public ToolUseBlock(String id, String toolName, String inputJson) {
        this.id = id;
        this.toolName = toolName;
        this.inputJson = inputJson;
    }

    public String getId() { return id; }
    public String getToolName() { return toolName; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
}

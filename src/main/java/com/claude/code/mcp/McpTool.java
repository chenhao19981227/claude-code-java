package com.claude.code.mcp;

public class McpTool {
    private String name;
    private String description;
    private String inputSchema;

    public McpTool(String name, String description, String inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInputSchema() { return inputSchema; }
}

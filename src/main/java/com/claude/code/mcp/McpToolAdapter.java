package com.claude.code.mcp;

import com.claude.code.tool.Tool;
import com.claude.code.tool.ToolResult;
import com.claude.code.tool.ToolUseContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class McpToolAdapter extends Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpClient client;
    private final String mcpToolName;
    private final String inputSchemaJson;

    public McpToolAdapter(McpClient client, McpTool mcpTool) {
        super("mcp_" + client.getServerName() + "_" + mcpTool.name(),
              mcpTool.description());
        this.client = client;
        this.mcpToolName = mcpTool.name();
        this.inputSchemaJson = mcpTool.inputSchema();
    }

    @Override
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> args;
        try {
            args = MAPPER.readValue(inputJson, new TypeReference<>() {});
        } catch (Exception e) {
            args = new java.util.HashMap<>();
        }

        McpToolResult result = client.callTool(mcpToolName, args);
        if (result.isError()) {
            return ToolResult.error(result.content());
        }
        return ToolResult.success(result.content());
    }

    @Override
    public String getInputSchemaJson() { return inputSchemaJson; }

    public McpClient getClient() { return client; }
    public String getMcpToolName() { return mcpToolName; }
}

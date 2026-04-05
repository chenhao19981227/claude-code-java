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
        super("mcp_" + client.getServerName() + "_" + mcpTool.getName(),
              mcpTool.getDescription());
        this.client = client;
        this.mcpToolName = mcpTool.getName();
        this.inputSchemaJson = mcpTool.getInputSchema();
    }

    @Override
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> args;
        try {
            args = MAPPER.readValue(inputJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            args = new java.util.HashMap<String, Object>();
        }

        McpToolResult result = client.callTool(mcpToolName, args);
        if (result.isError()) {
            return ToolResult.error(result.getContent());
        }
        return ToolResult.success(result.getContent());
    }

    @Override
    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public McpClient getClient() {
        return client;
    }

    public String getMcpToolName() {
        return mcpToolName;
    }
}

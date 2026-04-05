package com.claude.code.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerConfig config;
    private McpStdioTransport transport;
    private volatile boolean connected;

    public McpClient(McpServerConfig config) {
        this.config = config;
    }

    public String getServerName() {
        return config.getName();
    }

    public void connect() throws IOException {
        transport = new McpStdioTransport(config);
        try {
            transport.connect();
        } catch (IOException e) {
            throw new IOException("Failed to start MCP server '" + config.getName() + "': " + e.getMessage(), e);
        }

        try {
            // Send initialize request
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            Map<String, Object> capabilities = new HashMap<String, Object>();
            Map<String, Object> clientInfo = new LinkedHashMap<String, Object>();
            clientInfo.put("name", "claude-code-java");
            clientInfo.put("version", "1.0.0");

            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", capabilities);
            params.put("clientInfo", clientInfo);

            String initResponse = transport.sendRequest("initialize", params);

            // Send initialized notification
            transport.sendNotification("notifications/initialized", null);

            connected = true;
        } catch (Exception e) {
            transport.close();
            connected = false;
            throw new IOException("MCP handshake failed for '" + config.getName() + "': " + e.getMessage(), e);
        }
    }

    public List<McpTool> listTools() throws IOException {
        if (!connected) {
            throw new IOException("MCP client not connected: " + config.getName());
        }

        try {
            String response = transport.sendRequest("tools/list", null);
            JsonNode root = MAPPER.readTree(response);
            JsonNode result = root.get("result");
            if (result == null) {
                return new ArrayList<McpTool>();
            }
            JsonNode toolsNode = result.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                return new ArrayList<McpTool>();
            }

            List<McpTool> tools = new ArrayList<McpTool>();
            for (JsonNode toolNode : toolsNode) {
                String name = toolNode.has("name") ? toolNode.get("name").asText() : "unknown";
                String description = toolNode.has("description") ? toolNode.get("description").asText() : "";
                String inputSchema = "{}";
                if (toolNode.has("inputSchema")) {
                    inputSchema = MAPPER.writeValueAsString(toolNode.get("inputSchema"));
                }
                tools.add(new McpTool(name, description, inputSchema));
            }
            return tools;
        } catch (IOException e) {
            throw new IOException("Failed to list tools from MCP server '" + config.getName() + "': " + e.getMessage(), e);
        }
    }

    public McpToolResult callTool(String toolName, Map<String, Object> arguments) throws IOException {
        if (!connected) {
            throw new IOException("MCP client not connected: " + config.getName());
        }

        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", toolName);
        if (arguments != null) {
            params.put("arguments", arguments);
        } else {
            params.put("arguments", new HashMap<String, Object>());
        }

        try {
            String response = transport.sendRequest("tools/call", params);
            JsonNode root = MAPPER.readTree(response);
            JsonNode result = root.get("result");

            if (result == null) {
                return new McpToolResult("", false);
            }

            // Extract content array
            JsonNode contentNode = result.get("content");
            boolean isError = result.has("isError") && result.get("isError").asBoolean();

            StringBuilder sb = new StringBuilder();
            if (contentNode != null && contentNode.isArray()) {
                for (JsonNode item : contentNode) {
                    if ("text".equals(item.has("type") ? item.get("type").asText() : "")) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(item.has("text") ? item.get("text").asText() : "");
                    }
                }
            }

            return new McpToolResult(sb.toString(), isError);
        } catch (IOException e) {
            throw new IOException("Failed to call MCP tool '" + toolName + "' on '" + config.getName() + "': " + e.getMessage(), e);
        }
    }

    public void close() {
        if (transport != null) {
            transport.close();
        }
        connected = false;
    }

    public boolean isConnected() {
        return connected;
    }
}

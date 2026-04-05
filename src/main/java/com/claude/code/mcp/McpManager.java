package com.claude.code.mcp;

import com.claude.code.tool.Tool;

import java.util.ArrayList;
import java.util.List;

public class McpManager {
    private final List<McpClient> clients = new ArrayList<McpClient>();
    private final List<McpToolAdapter> adapters = new ArrayList<McpToolAdapter>();

    public void init(List<McpServerConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return;
        }

        for (McpServerConfig config : configs) {
            try {
                McpClient client = new McpClient(config);
                client.connect();

                List<McpTool> tools = client.listTools();
                System.out.println("MCP server '" + config.getName() + "' connected with " + tools.size() + " tools");

                for (McpTool tool : tools) {
                    McpToolAdapter adapter = new McpToolAdapter(client, tool);
                    adapters.add(adapter);
                    System.out.println("  Registered MCP tool: " + adapter.getName());
                }

                clients.add(client);
            } catch (Exception e) {
                System.err.println("Warning: Failed to connect to MCP server '" + config.getName() + "': " + e.getMessage());
            }
        }
    }

    public List<Tool> getToolAdapters() {
        List<Tool> result = new ArrayList<Tool>();
        result.addAll(adapters);
        return result;
    }

    public List<McpToolAdapter> getMcpAdapters() {
        return new ArrayList<McpToolAdapter>(adapters);
    }

    public void shutdown() {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                // ignore
            }
        }
        clients.clear();
        adapters.clear();
    }
}

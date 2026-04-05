package com.claude.code.mcp;

import com.claude.code.config.AppProperties;
import com.claude.code.tool.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class McpManager {
    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private final AppProperties appProperties;
    private final List<McpClient> clients = new ArrayList<>();
    private final List<McpToolAdapter> adapters = new ArrayList<>();

    public McpManager(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        var configs = appProperties.getMcpServerConfigs();
        if (configs.isEmpty()) return;

        for (var config : configs) {
            try {
                var client = new McpClient(config);
                client.connect();

                var tools = client.listTools();
                log.info("MCP server '{}' connected with {} tools", config.getName(), tools.size());

                for (var tool : tools) {
                    var adapter = new McpToolAdapter(client, tool);
                    adapters.add(adapter);
                    log.info("  Registered MCP tool: {}", adapter.getName());
                }
                clients.add(client);
            } catch (Exception e) {
                log.warn("Failed to connect to MCP server '{}': {}", config.getName(), e.getMessage());
            }
        }
    }

    public List<Tool> getToolAdapters() {
        return new ArrayList<>(adapters);
    }

    public List<McpToolAdapter> getMcpAdapters() {
        return new ArrayList<>(adapters);
    }

    @PreDestroy
    public void shutdown() {
        for (var client : clients) {
            try { client.close(); } catch (Exception ignored) {}
        }
        clients.clear();
        adapters.clear();
    }
}

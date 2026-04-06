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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
public class McpManager {
    private static final Logger log = LoggerFactory.getLogger(McpManager.class);
    private static final long INIT_TIMEOUT_MS = 60_000;

    private final AppProperties appProperties;
    private final List<McpClient> clients = new ArrayList<>();
    private final List<McpToolAdapter> adapters = new ArrayList<>();
    private final CountDownLatch initLatch = new CountDownLatch(1);

    public McpManager(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() {
        // Run MCP init in a virtual thread to avoid blocking Spring Boot startup
        Thread.startVirtualThread(() -> {
            try {
                var configs = appProperties.getMcpServerConfigs();
                if (configs.isEmpty()) {
                    initLatch.countDown();
                    return;
                }

                for (var config : configs) {
                    try {
                        var client = new McpClient(config);
                        client.connect();

                        var tools = client.listTools();
                        log.info("MCP server '{}' connected with {} tools", config.getName(), tools.size());

                        synchronized (adapters) {
                            for (var tool : tools) {
                                var adapter = new McpToolAdapter(client, tool);
                                adapters.add(adapter);
                                log.info("  Registered MCP tool: {}", adapter.getName());
                            }
                        }
                        clients.add(client);
                    } catch (Exception e) {
                        log.warn("Failed to connect to MCP server '{}': {}", config.getName(), e.getMessage());
                    }
                }
            } finally {
                initLatch.countDown();
            }
        });
    }

    /**
     * Wait for MCP initialization to complete (or timeout).
     * Called by QueryEngine before registering MCP tools.
     */
    public boolean awaitInit(long timeoutMs) {
        try {
            return initLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public List<Tool> getToolAdapters() {
        synchronized (adapters) {
            return new ArrayList<>(adapters);
        }
    }

    public List<McpToolAdapter> getMcpAdapters() {
        synchronized (adapters) {
            return new ArrayList<>(adapters);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (var client : clients) {
            try { client.close(); } catch (Exception ignored) {}
        }
        clients.clear();
        synchronized (adapters) {
            adapters.clear();
        }
    }
}

package com.claude.code.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class McpStdioTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long RESPONSE_TIMEOUT_MS = 30000;

    private final McpServerConfig config;
    private Process process;
    private BufferedWriter writer;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, AtomicReference<String>> pendingResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CountDownLatch> pendingLatches = new ConcurrentHashMap<>();
    private volatile boolean running;

    public McpStdioTransport(McpServerConfig config) {
        this.config = config;
    }

    public void connect() throws IOException {
        var pb = new ProcessBuilder();
        pb.command().add(config.getCommand());
        if (config.getArgs() != null) {
            for (var arg : config.getArgs()) {
                pb.command().add(arg);
            }
        }

        if (config.getEnv() != null) {
            Map<String, String> env = pb.environment();
            env.putAll(config.getEnv());
        }

        pb.redirectErrorStream(false);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        running = true;

        Thread.startVirtualThread(this::readLoop);
    }

    private void readLoop() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonNode node = MAPPER.readTree(line);
                    JsonNode idNode = node.get("id");
                    if (idNode != null && idNode.isInt()) {
                        int id = idNode.asInt();
                        var ref = pendingResponses.get(id);
                        var latch = pendingLatches.get(id);
                        if (ref != null) ref.set(line);
                        if (latch != null) latch.countDown();
                    }
                } catch (Exception e) {
                    System.err.println("MCP [" + config.getName() + "] parse error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("MCP [" + config.getName() + "] read error: " + e.getMessage());
            }
        } finally {
            running = false;
        }
    }

    public String sendRequest(String method, Map<String, Object> params) throws IOException {
        int id = requestId.incrementAndGet();
        var responseRef = new AtomicReference<String>();
        var latch = new CountDownLatch(1);
        pendingResponses.put(id, responseRef);
        pendingLatches.put(id, latch);

        try {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null && !params.isEmpty()) {
                request.set("params", MAPPER.valueToTree(params));
            }

            String json = MAPPER.writeValueAsString(request);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            boolean received = latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!received) {
                throw new IOException("MCP request timeout: " + method + " to " + config.getName());
            }

            String response = responseRef.get();
            if (response == null) {
                throw new IOException("MCP request failed: no response for " + method);
            }

            JsonNode responseNode = MAPPER.readTree(response);
            JsonNode errorNode = responseNode.get("error");
            if (errorNode != null) {
                String errorMsg = errorNode.has("message") ? errorNode.get("message").asText() : errorNode.toString();
                throw new IOException("MCP error for " + method + ": " + errorMsg);
            }

            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP request interrupted: " + method + " to " + config.getName(), e);
        } finally {
            pendingResponses.remove(id);
            pendingLatches.remove(id);
        }
    }

    public void sendNotification(String method, Map<String, Object> params) throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null && !params.isEmpty()) {
            notification.set("params", MAPPER.valueToTree(params));
        }

        String json = MAPPER.writeValueAsString(notification);
        synchronized (writer) {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }
    }

    public void close() {
        running = false;
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}

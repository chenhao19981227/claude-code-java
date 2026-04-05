package com.claude.code.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
    private final ConcurrentHashMap<Integer, AtomicReference<String>> pendingResponses = new ConcurrentHashMap<Integer, AtomicReference<String>>();
    private final ConcurrentHashMap<Integer, CountDownLatch> pendingLatches = new ConcurrentHashMap<Integer, CountDownLatch>();
    private volatile boolean running;
    private Thread readerThread;

    public McpStdioTransport(McpServerConfig config) {
        this.config = config;
    }

    public void connect() throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().add(config.getCommand());
        if (config.getArgs() != null && config.getArgs().length > 0) {
            for (String arg : config.getArgs()) {
                pb.command().add(arg);
            }
        }

        if (config.getEnv() != null) {
            Map<String, String> env = pb.environment();
            for (Map.Entry<String, String> entry : config.getEnv().entrySet()) {
                env.put(entry.getKey(), entry.getValue());
            }
        }

        pb.redirectErrorStream(false);
        process = pb.start();

        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        running = true;

        // Start reader thread
        readerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "mcp-reader-" + config.getName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonNode node = MAPPER.readTree(line);
                    JsonNode idNode = node.get("id");
                    if (idNode != null && idNode.isInt()) {
                        int id = idNode.asInt();
                        AtomicReference<String> ref = pendingResponses.get(id);
                        CountDownLatch latch = pendingLatches.get(id);
                        if (ref != null) {
                            ref.set(line);
                        }
                        if (latch != null) {
                            latch.countDown();
                        }
                    }
                    // Notifications and other messages are silently consumed
                } catch (Exception e) {
                    // Skip malformed JSON lines
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
        AtomicReference<String> responseRef = new AtomicReference<String>();
        CountDownLatch latch = new CountDownLatch(1);
        pendingResponses.put(id, responseRef);
        pendingLatches.put(id, latch);

        try {
            // Build JSON-RPC request
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

            // Wait for response
            boolean received = false;
            try {
                received = latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("MCP request interrupted: " + method + " to " + config.getName(), e);
            }
            if (!received) {
                throw new IOException("MCP request timeout: " + method + " to " + config.getName());
            }

            String response = responseRef.get();
            if (response == null) {
                throw new IOException("MCP request failed: no response for " + method);
            }

            // Check for error
            JsonNode responseNode = MAPPER.readTree(response);
            JsonNode errorNode = responseNode.get("error");
            if (errorNode != null) {
                String errorMsg = errorNode.has("message") ? errorNode.get("message").asText() : errorNode.toString();
                throw new IOException("MCP error for " + method + ": " + errorMsg);
            }

            return response;
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
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            // ignore
        }
        if (process != null) {
            process.destroy();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}

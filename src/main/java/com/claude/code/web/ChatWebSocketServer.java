package com.claude.code.web;

import com.claude.code.query.QueryEngine;
import com.claude.code.state.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatWebSocketServer extends WebSocketServer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueryEngine queryEngine;
    private final Settings settings;
    private volatile WebSocket activeConn;
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor();

    public ChatWebSocketServer(int port, QueryEngine queryEngine, Settings settings) {
        super(new InetSocketAddress(port));
        this.queryEngine = queryEngine;
        this.settings = settings;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        activeConn = conn;
        System.out.println("[WS] Client connected: " + conn.getRemoteSocketAddress());
        sendJson(conn, "event", "connected", "model", settings.getEffectiveModel());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[WS] Client disconnected: code=" + code + " reason=" + reason + " remote=" + remote);
        if (activeConn == conn) {
            activeConn = null;
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> msg = MAPPER.readValue(message, java.util.Map.class);
            String type = String.valueOf(msg.get("type"));

            if ("chat".equals(type)) {
                String content = String.valueOf(msg.get("content"));
                if (content == null || content.trim().isEmpty()) return;
                System.out.println("[WS] Chat: " + content.substring(0, Math.min(80, content.length())));

                queryEngine.submitMessage(content, new QueryEngine.QueryCallback() {
                    @Override
                    public void onTextDelta(String text) {
                        sendToActive("event", "text_delta", "text", text);
                    }

                    @Override
                    public void onReasoningDelta(String text) {
                        sendToActive("event", "reasoning_delta", "text", text);
                    }

                    @Override
                    public void onToolStart(String toolName, String toolUseId, String input) {
                        System.out.println("[WS] Tool start: " + toolName);
                        sendToActive("event", "tool_start",
                            "toolName", toolName,
                            "toolUseId", toolUseId,
                            "input", input);
                    }

                    @Override
                    public void onToolResult(String toolUseId, String result, boolean isError) {
                        String truncated = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                        System.out.println("[WS] Tool result: " + (isError ? "ERROR " : "") + truncated);
                        sendToActive("event", "tool_result",
                            "toolUseId", toolUseId,
                            "result", result,
                            "isError", isError);
                    }

                    @Override
                    public void onError(String error) {
                        System.err.println("[WS] Error: " + error);
                        sendToActive("event", "error", "error", error);
                    }

                    @Override
                    public void onComplete() {
                        System.out.println("[WS] Complete");
                        sendToActive("event", "done");
                    }
                });
            } else if ("abort".equals(type)) {
                queryEngine.abort();
            }
        } catch (Exception e) {
            sendToActive("event", "error", "error", "Invalid message: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WS] Error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        setConnectionLostTimeout(0);
        pingScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                WebSocket conn = activeConn;
                if (conn != null && conn.isOpen()) {
                    try { conn.sendPing(); } catch (Exception ignored) {}
                }
            }
        }, 30, 30, TimeUnit.SECONDS);
        System.out.println("[WS] Server started on port " + getPort());
    }

    private void sendToActive(Object... kvPairs) {
        WebSocket conn = activeConn;
        if (conn == null || !conn.isOpen()) return;
        try {
            sendJson(conn, kvPairs);
        } catch (Exception e) {
            System.err.println("[WS] Send failed: " + e.getMessage());
        }
    }

    private void sendJson(WebSocket conn, Object... kvPairs) {
        if (conn == null || !conn.isOpen()) return;
        try {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < kvPairs.length; i += 2) {
                if (i > 0) sb.append(",");
                String key = String.valueOf(kvPairs[i]);
                Object val = kvPairs[i + 1];
                sb.append("\"").append(escape(key)).append("\":");
                if (val instanceof String) {
                    sb.append("\"").append(escape((String) val)).append("\"");
                } else if (val instanceof Boolean) {
                    sb.append(val);
                } else if (val instanceof Number) {
                    sb.append(val);
                } else {
                    sb.append("\"").append(escape(String.valueOf(val))).append("\"");
                }
            }
            sb.append("}");
            conn.send(sb.toString());
        } catch (Exception e) {
            System.err.println("[WS] Send failed: " + e.getMessage());
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

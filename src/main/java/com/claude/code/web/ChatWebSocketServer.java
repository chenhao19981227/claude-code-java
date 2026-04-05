package com.claude.code.web;

import com.claude.code.query.QueryEngine;
import com.claude.code.session.Session;
import com.claude.code.state.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
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
        // Send current session info
        String sessionId = queryEngine.getCurrentSessionId();
        sendJson(conn, "event", "session_info", "sessionId", sessionId != null ? sessionId : "");
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

                // Track whether this creates a new session
                final String sessionIdBefore = queryEngine.getCurrentSessionId();

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
                        // Notify if a new session was created during this chat
                        String sessionIdAfter = queryEngine.getCurrentSessionId();
                        if (sessionIdBefore == null && sessionIdAfter != null) {
                            Session currentSession = queryEngine.getCurrentSession();
                            if (currentSession != null) {
                                sendToActive("event", "session_created", "sessionId", currentSession.getId(), "title", currentSession.getTitle());
                            }
                        }
                        sendToActive("event", "done");
                    }
                });
            } else if ("abort".equals(type)) {
                queryEngine.abort();
            } else if ("new_session".equals(type)) {
                handleNewSession(conn);
            } else if ("load_session".equals(type)) {
                String sessionId = String.valueOf(msg.get("sessionId"));
                handleLoadSession(conn, sessionId);
            } else if ("list_sessions".equals(type)) {
                handleListSessions(conn);
            } else if ("delete_session".equals(type)) {
                String sessionId = String.valueOf(msg.get("sessionId"));
                handleDeleteSession(conn, sessionId);
            } else if ("rename_session".equals(type)) {
                String sessionId = String.valueOf(msg.get("sessionId"));
                String title = String.valueOf(msg.get("title"));
                handleRenameSession(conn, sessionId, title);
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

    private void handleNewSession(WebSocket conn) {
        Session session = queryEngine.newSession();
        sendJson(conn, "event", "session_created", "sessionId", session.getId(), "title", session.getTitle());
    }

    private void handleLoadSession(WebSocket conn, String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sendJson(conn, "event", "error", "error", "sessionId is required");
            return;
        }
        Session session = queryEngine.loadSession(sessionId);
        if (session == null) {
            sendJson(conn, "event", "error", "error", "Session not found: " + sessionId);
            return;
        }
        try {
            List<Map<String, Object>> msgList = new java.util.ArrayList<>();
            for (com.claude.code.session.SessionMessage sm : session.getMessages()) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("role", sm.getRole());
                m.put("content", sm.getContent());
                if (sm.getReasoning() != null && !sm.getReasoning().isEmpty()) {
                    m.put("reasoning", sm.getReasoning());
                }
                msgList.add(m);
            }
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("event", "session_loaded");
            response.put("sessionId", session.getId());
            response.put("messages", msgList);
            response.put("title", session.getTitle());
            conn.send(MAPPER.writeValueAsString(response));
        } catch (Exception e) {
            System.err.println("[WS] Send failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleListSessions(WebSocket conn) {
        List<Map<String, Object>> sessions = queryEngine.listSessions();
        try {
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("event", "sessions_list");
            response.put("sessions", sessions);
            conn.send(MAPPER.writeValueAsString(response));
        } catch (Exception e) {
            System.err.println("[WS] Send failed: " + e.getMessage());
        }
    }

    private void handleDeleteSession(WebSocket conn, String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sendJson(conn, "event", "error", "error", "sessionId is required");
            return;
        }
        queryEngine.deleteSession(sessionId);
        sendJson(conn, "event", "session_deleted", "sessionId", sessionId);
    }

    private void handleRenameSession(WebSocket conn, String sessionId, String title) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sendJson(conn, "event", "error", "error", "sessionId is required");
            return;
        }
        boolean success = queryEngine.renameSession(sessionId, title);
        if (success) {
            sendJson(conn, "event", "session_renamed", "sessionId", sessionId, "title", title);
        } else {
            sendJson(conn, "event", "error", "error", "Session not found: " + sessionId);
        }
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

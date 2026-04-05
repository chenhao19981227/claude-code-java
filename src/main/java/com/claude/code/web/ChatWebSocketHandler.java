package com.claude.code.web;

import com.claude.code.query.QueryEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QueryEngine queryEngine;

    private volatile WebSocketSession activeSession;

    public ChatWebSocketHandler(QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        activeSession = session;
        log.info("WebSocket client connected: {}", session.getRemoteAddress());
        sendJson(session, "event", "connected", "model", queryEngine.getAppStore().getState().getMainLoopModel());
        String sessionId = queryEngine.getCurrentSessionId();
        sendJson(session, "event", "session_info", "sessionId", sessionId != null ? sessionId : "");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket client disconnected: code={} reason={}", status.getCode(), status.getReason());
        if (activeSession == session) {
            activeSession = null;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> msg;
        try {
            msg = MAPPER.readValue(message.getPayload(), Map.class);
        } catch (Exception e) {
            sendToActive("event", "error", "error", "Invalid message: " + e.getMessage());
            return;
        }

        String type = String.valueOf(msg.get("type"));

        switch (type) {
            case "chat" -> handleChat(msg);
            case "abort" -> queryEngine.abort();
            case "new_session" -> handleNewSession(session);
            case "load_session" -> handleLoadSession(session, msg);
            case "list_sessions" -> handleListSessions(session);
            case "delete_session" -> handleDeleteSession(session, msg);
            case "rename_session" -> handleRenameSession(session, msg);
            default -> sendToActive("event", "error", "error", "Unknown message type: " + type);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket error: {}", exception.getMessage());
    }

    private void handleChat(Map<String, Object> msg) {
        String content = String.valueOf(msg.get("content"));
        if (content == null || content.trim().isEmpty()) return;
        log.info("Chat: {}", content.substring(0, Math.min(80, content.length())));

        final String sessionIdBefore = queryEngine.getCurrentSessionId();

        queryEngine.submitMessage(content, new QueryEngine.QueryCallback() {
            @Override public void onTextDelta(String text) {
                sendToActive("event", "text_delta", "text", text);
            }

            @Override public void onReasoningDelta(String text) {
                sendToActive("event", "reasoning_delta", "text", text);
            }

            @Override public void onToolStart(String toolName, String toolUseId, String input) {
                log.info("Tool start: {}", toolName);
                sendToActive("event", "tool_start", "toolName", toolName, "toolUseId", toolUseId, "input", input);
            }

            @Override public void onToolResult(String toolUseId, String result, boolean isError) {
                String truncated = result.length() > 200 ? result.substring(0, 200) + "..." : result;
                log.info("Tool result: {} {}", isError ? "ERROR " : "", truncated);
                sendToActive("event", "tool_result", "toolUseId", toolUseId, "result", result, "isError", isError);
            }

            @Override public void onError(String error) {
                log.error("Error: {}", error);
                sendToActive("event", "error", "error", error);
            }

            @Override public void onComplete() {
                log.info("Complete");
                String sessionIdAfter = queryEngine.getCurrentSessionId();
                if (sessionIdBefore == null && sessionIdAfter != null) {
                    var currentSession = queryEngine.getCurrentSession();
                    if (currentSession != null) {
                        sendToActive("event", "session_created", "sessionId", currentSession.getSessionId(), "title", currentSession.getTitle());
                    }
                }
                sendToActive("event", "done");
            }
        });
    }

    private void handleNewSession(WebSocketSession session) throws Exception {
        var sessionEntity = queryEngine.newSession();
        sendJson(session, "event", "session_created", "sessionId", sessionEntity.getSessionId(), "title", sessionEntity.getTitle());
    }

    private void handleLoadSession(WebSocketSession session, Map<String, Object> msg) throws Exception {
        String sessionId = String.valueOf(msg.get("sessionId"));
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sendJson(session, "event", "error", "error", "sessionId is required");
            return;
        }
        var loaded = queryEngine.loadSession(sessionId);
        if (loaded == null) {
            sendJson(session, "event", "error", "error", "Session not found: " + sessionId);
            return;
        }
        var msgList = new java.util.ArrayList<Map<String, Object>>();
        for (var sm : loaded.getMessages()) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("role", sm.getRole());
            m.put("content", sm.getContent());
            if (sm.getReasoning() != null && !sm.getReasoning().isEmpty()) {
                m.put("reasoning", sm.getReasoning());
            }
            msgList.add(m);
        }
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("event", "session_loaded");
        response.put("sessionId", loaded.getSessionId());
        response.put("messages", msgList);
        response.put("title", loaded.getTitle());
        session.sendMessage(new TextMessage(MAPPER.writeValueAsString(response)));
    }

    private void handleListSessions(WebSocketSession session) throws Exception {
        var sessions = queryEngine.listSessions();
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("event", "sessions_list");
        response.put("sessions", sessions);
        session.sendMessage(new TextMessage(MAPPER.writeValueAsString(response)));
    }

    private void handleDeleteSession(WebSocketSession session, Map<String, Object> msg) throws Exception {
        String sessionId = String.valueOf(msg.get("sessionId"));
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sendJson(session, "event", "error", "error", "sessionId is required");
            return;
        }
        queryEngine.deleteSession(sessionId);
        sendJson(session, "event", "session_deleted", "sessionId", sessionId);
    }

    private void handleRenameSession(WebSocketSession session, Map<String, Object> msg) throws Exception {
        String sessionId = String.valueOf(msg.get("sessionId"));
        String title = String.valueOf(msg.get("title"));
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sendJson(session, "event", "error", "error", "sessionId is required");
            return;
        }
        boolean success = queryEngine.renameSession(sessionId, title);
        if (success) {
            sendJson(session, "event", "session_renamed", "sessionId", sessionId, "title", title);
        } else {
            sendJson(session, "event", "error", "error", "Session not found: " + sessionId);
        }
    }

    private void sendToActive(Object... kvPairs) {
        var session = activeSession;
        if (session == null || !session.isOpen()) return;
        try {
            sendJson(session, kvPairs);
        } catch (Exception e) {
            log.error("WebSocket send failed: {}", e.getMessage());
        }
    }

    private void sendJson(WebSocketSession session, Object... kvPairs) {
        if (session == null || !session.isOpen()) return;
        try {
            var sb = new StringBuilder("{");
            for (int i = 0; i < kvPairs.length; i += 2) {
                if (i > 0) sb.append(",");
                String key = String.valueOf(kvPairs[i]);
                Object val = kvPairs[i + 1];
                sb.append("\"").append(escape(key)).append("\":");
                if (val instanceof String s) {
                    sb.append("\"").append(escape(s)).append("\"");
                } else if (val instanceof Boolean || val instanceof Number) {
                    sb.append(val);
                } else {
                    sb.append("\"").append(escape(String.valueOf(val))).append("\"");
                }
            }
            sb.append("}");
            session.sendMessage(new TextMessage(sb.toString()));
        } catch (Exception e) {
            log.error("WebSocket send failed: {}", e.getMessage());
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

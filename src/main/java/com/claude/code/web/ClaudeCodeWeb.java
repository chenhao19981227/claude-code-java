package com.claude.code.web;

import com.claude.code.api.ApiClient;
import com.claude.code.api.ApiProvider;
import com.claude.code.api.AnthropicClient;
import com.claude.code.api.OpenAiCompatibleClient;
import com.claude.code.query.QueryEngine;
import com.claude.code.session.Session;
import com.claude.code.session.SessionManager;
import com.claude.code.state.Settings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClaudeCodeWeb {
    private static final int DEFAULT_PORT = 8080;
    private static final String WEB_DIR = "web";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String webDir = WEB_DIR;

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--web-dir".equals(args[i]) && i + 1 < args.length) {
                webDir = args[++i];
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                System.out.println("Usage: java -jar claude-code-java.jar --web [--port 8080] [--web-dir ./web]");
                return;
            }
        }

        Settings settings = Settings.load();
        if (!settings.hasApiKey()) {
            System.err.println("Error: API key not configured. Edit: " + Settings.getProjectConfigPath());
            System.exit(1);
        }

        String providerName = settings.getProvider();
        String apiKey = settings.getApiKey();
        String model = settings.getEffectiveModel();
        ApiProvider provider = ApiProvider.fromString(providerName);

        String baseUrl = settings.getBaseUrl();
        boolean hasCustomBaseUrl = baseUrl != null && !baseUrl.trim().isEmpty();
        String resolvedBaseUrl = hasCustomBaseUrl ? baseUrl : null;

        ApiClient apiClient;
        if (provider == ApiProvider.ANTHROPIC) {
            apiClient = new AnthropicClient(apiKey, model, resolvedBaseUrl);
        } else {
            apiClient = new OpenAiCompatibleClient(apiKey, model, provider, resolvedBaseUrl);
        }

        String workingDir = System.getProperty("user.dir");
        QueryEngine queryEngine = new QueryEngine(apiClient, workingDir, settings);

        ChatWebSocketServer wsServer = new ChatWebSocketServer(port + 1, queryEngine, settings);
        wsServer.setReuseAddr(true);
        wsServer.start();

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        Path webPath = Paths.get(webDir).toAbsolutePath();

        // Session REST API endpoints (registered before static file handler for priority)
        httpServer.createContext("/api/sessions", new SessionApiHandler(queryEngine));

        httpServer.createContext("/", new StaticFileHandler(webPath, port));
        httpServer.setExecutor(null);
        httpServer.start();

        System.out.println();
        System.out.println("  Claude Code Web UI");
        System.out.println("  Provider: " + provider.getTitle() + " | Model: " + model);
        System.out.println("  HTTP:      http://localhost:" + port);
        System.out.println("  WebSocket: ws://localhost:" + (port + 1));
        System.out.println("  Web dir:   " + webPath);
        System.out.println();
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();
    }

    private static class SessionApiHandler implements HttpHandler {
        private final QueryEngine queryEngine;

        SessionApiHandler(QueryEngine queryEngine) {
            this.queryEngine = queryEngine;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            try {
                // GET /api/sessions - list all sessions
                if ("GET".equals(method) && "/api/sessions".equals(path)) {
                    List<Map<String, Object>> sessions = queryEngine.listSessions();
                    sendJsonResponse(exchange, 200, sessions);
                    return;
                }

                // POST /api/sessions - create new session
                if ("POST".equals(method) && "/api/sessions".equals(path)) {
                    String body = readRequestBody(exchange);
                    String title = "New Session";
                    if (body != null && !body.trim().isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> map = MAPPER.readValue(body, Map.class);
                            if (map.containsKey("title") && map.get("title") != null) {
                                title = String.valueOf(map.get("title"));
                            }
                        } catch (Exception ignored) {}
                    }
                    Session session = queryEngine.newSession();
                    session.setTitle(title);
                    Map<String, Object> result = new HashMap<>();
                    result.put("id", session.getId());
                    result.put("title", session.getTitle());
                    result.put("createdAt", session.getCreatedAt());
                    result.put("updatedAt", session.getUpdatedAt());
                    sendJsonResponse(exchange, 201, result);
                    return;
                }

                // Extract session ID from path for specific session operations
                // Path: /api/sessions/{id}
                if (path.startsWith("/api/sessions/") && path.length() > "/api/sessions/".length()) {
                    String sessionId = path.substring("/api/sessions/".length());

                    // GET /api/sessions/{id} - load session
                    if ("GET".equals(method)) {
                        Session session = queryEngine.loadSession(sessionId);
                        if (session == null) {
                            sendJsonResponse(exchange, 404, createError("Session not found"));
                            return;
                        }
                        sendJsonResponse(exchange, 200, session);
                        return;
                    }

                    // DELETE /api/sessions/{id} - delete session
                    if ("DELETE".equals(method)) {
                        queryEngine.deleteSession(sessionId);
                        Map<String, Object> result = new HashMap<>();
                        result.put("deleted", true);
                        result.put("id", sessionId);
                        sendJsonResponse(exchange, 200, result);
                        return;
                    }

                    // PUT /api/sessions/{id} - rename session
                    if ("PUT".equals(method)) {
                        String body = readRequestBody(exchange);
                        String title = null;
                        if (body != null && !body.trim().isEmpty()) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> map = MAPPER.readValue(body, Map.class);
                                if (map.containsKey("title") && map.get("title") != null) {
                                    title = String.valueOf(map.get("title"));
                                }
                            } catch (Exception ignored) {}
                        }
                        if (title == null) {
                            sendJsonResponse(exchange, 400, createError("title is required"));
                            return;
                        }
                        boolean success = queryEngine.renameSession(sessionId, title);
                        if (success) {
                            Map<String, Object> result = new HashMap<>();
                            result.put("id", sessionId);
                            result.put("title", title);
                            sendJsonResponse(exchange, 200, result);
                        } else {
                            sendJsonResponse(exchange, 404, createError("Session not found"));
                        }
                        return;
                    }
                }

                // Method not allowed or not found
                sendJsonResponse(exchange, 404, createError("Not found"));
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, createError("Internal error: " + e.getMessage()));
            }
        }

        private String readRequestBody(HttpExchange exchange) throws IOException {
            InputStream is = exchange.getRequestBody();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }

        private void sendJsonResponse(HttpExchange exchange, int code, Object data) throws IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(data);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(code, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }

        private Map<String, Object> createError(String message) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", message);
            return error;
        }
    }

    private static class StaticFileHandler implements HttpHandler {
        private final Path webRoot;
        private final int wsPort;

        StaticFileHandler(Path webRoot, int wsPort) {
            this.webRoot = webRoot;
            this.wsPort = wsPort;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";

            Path filePath = webRoot.resolve(path.substring(1)).normalize();

            if (!filePath.startsWith(webRoot)) {
                sendError(exchange, 403, "Forbidden");
                return;
            }

            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                String contentType = getContentType(filePath.toString());
                byte[] bytes = Files.readAllBytes(filePath);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
                exchange.getResponseHeaders().set("Pragma", "no-cache");
                exchange.getResponseHeaders().set("Expires", "0");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            } else {
                sendError(exchange, 404, "Not Found");
            }
        }

        private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            byte[] bytes = msg.getBytes("UTF-8");
            exchange.sendResponseHeaders(code, bytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(bytes);
            os.close();
        }

        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            if (path.endsWith(".json")) return "application/json; charset=utf-8";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}

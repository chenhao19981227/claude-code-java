package com.claude.code.web;

import com.claude.code.api.ApiClient;
import com.claude.code.api.ApiProvider;
import com.claude.code.api.AnthropicClient;
import com.claude.code.api.OpenAiCompatibleClient;
import com.claude.code.query.QueryEngine;
import com.claude.code.state.Settings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClaudeCodeWeb {
    private static final int DEFAULT_PORT = 8080;
    private static final String WEB_DIR = "web";

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
            System.err.println("Error: API key not configured. Edit: " + Settings.getConfigPath());
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

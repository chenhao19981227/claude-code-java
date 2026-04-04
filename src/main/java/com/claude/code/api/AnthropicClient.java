package com.claude.code.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.BufferedSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AnthropicClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_VERSION = "2023-06-01";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String defaultModel;
    private final RetryHandler retryHandler;

    public AnthropicClient(String apiKey, String defaultModel) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel != null ? defaultModel : "claude-sonnet-4-20250514";
        this.baseUrl = System.getenv().getOrDefault("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        this.retryHandler = new RetryHandler(3, 1000, 30000, 0.5);
    }

    public void streamMessage(StreamRequest request, StreamListener listener) {
        try {
            String body = buildRequestBody(request);
            Request httpRequest = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("content-type", "application/json")
                .addHeader("accept", "text/event-stream")
                .post(RequestBody.create(body, JSON))
                .build();

            httpClient.newCall(httpRequest).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { listener.onError(e); }
                @Override public void onResponse(Call call, Response response) {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            String errorBody = responseBody != null ? responseBody.string() : "Unknown error";
                            listener.onError(new AnthropicApiException(response.code(), parseErrorType(errorBody), errorBody));
                            return;
                        }
                        BufferedSource source = responseBody.source();
                        parseSSE(source, listener);
                    } catch (Exception e) {
                        listener.onError(e);
                    } finally {
                        listener.onComplete();
                    }
                }
            });
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    public Map<String, Object> sendMessageSync(StreamRequest request) throws Exception {
        String body = buildRequestBody(request);
        Request httpRequest = new Request.Builder()
            .url(baseUrl + "/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(RequestBody.create(body, JSON))
            .build();

        Response response = httpClient.newCall(httpRequest).execute();
        String respBody = response.body().string();
        if (!response.isSuccessful()) {
            throw new AnthropicApiException(response.code(), parseErrorType(respBody), respBody);
        }
        return MAPPER.readValue(respBody, Map.class);
    }

    private String buildRequestBody(StreamRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escape(request.getModel() != null ? request.getModel() : defaultModel)).append("\",");
        sb.append("\"max_tokens\":").append(request.getMaxTokens()).append(",");
        sb.append("\"stream\":").append(request.isStream()).append(",");

        // system
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            sb.append("\"system\":[");
            for (int i = 0; i < request.getSystemPrompt().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"type\":\"text\",\"text\":\"").append(escape(request.getSystemPrompt().get(i))).append("\"}");
            }
            sb.append("],");
        }

        // messages
        sb.append("\"messages\":").append(buildMessagesJson(request.getMessages())).append(",");

        // tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            sb.append("\"tools\":").append(buildToolsJson(request.getTools())).append(",");
        }

        if (request.getTemperature() != null) {
            sb.append("\"temperature\":").append(request.getTemperature()).append(",");
        }

        // Remove trailing comma
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    private String buildMessagesJson(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(",");
            Map<String, Object> msg = messages.get(i);
            sb.append("{\"role\":\"").append(msg.get("role")).append("\",");
            sb.append("\"content\":").append(buildContentJson(msg.get("content")));
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private Object buildContentJson(Object content) {
        if (content instanceof String) {
            return "\"" + escape((String) content) + "\"";
        } else if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            List<?> blocks = (List<?>) content;
            for (int i = 0; i < blocks.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(blockToJson(blocks.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"\"";
    }

    private String blockToJson(Object block) {
        if (!(block instanceof Map)) return "\"\"";
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) block;
        String type = String.valueOf(m.get("type"));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"").append(escape(type)).append("\"");
        if ("text".equals(type)) {
            sb.append(",\"text\":\"").append(escape(String.valueOf(m.get("text")))).append("\"");
        } else if ("tool_use".equals(type)) {
            sb.append(",\"id\":\"").append(escape(String.valueOf(m.get("id")))).append("\"");
            sb.append(",\"name\":\"").append(escape(String.valueOf(m.get("name")))).append("\"");
            sb.append(",\"input\":").append(m.get("input") instanceof Map ? mapToJson((Map<String, Object>) m.get("input")) : "{}");
        } else if ("tool_result".equals(type)) {
            sb.append(",\"tool_use_id\":\"").append(escape(String.valueOf(m.get("tool_use_id")))).append("\"");
            sb.append(",\"content\":\"").append(escape(String.valueOf(m.get("content")))).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof String) sb.append("\"").append(escape((String) v)).append("\"");
            else if (v instanceof Number) sb.append(v);
            else if (v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(escape(String.valueOf(v))).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildToolsJson(List<Map<String, Object>> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) sb.append(",");
            Map<String, Object> tool = tools.get(i);
            sb.append("{\"name\":\"").append(escape(String.valueOf(tool.get("name")))).append("\",");
            sb.append("\"description\":\"").append(escape(String.valueOf(tool.get("description")))).append("\",");
            sb.append("\"input_schema\":").append(tool.get("input_schema") instanceof Map ? mapToJson((Map<String, Object>) tool.get("input_schema")) : "{}");
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private void parseSSE(BufferedSource source, StreamListener listener) throws IOException {
        StringBuilder dataBuffer = new StringBuilder();
        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) break;
            if (line.isEmpty()) {
                if (dataBuffer.length() > 0) {
                    String data = dataBuffer.toString();
                    dataBuffer.setLength(0);
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        if (node != null) processEvent(node, listener);
                    } catch (Exception e) {
                        // skip unparseable events
                    }
                }
            } else if (line.startsWith("data: ")) {
                if (dataBuffer.length() > 0) dataBuffer.append("\n");
                dataBuffer.append(line.substring(6));
            }
            // skip event: lines and comments
        }
    }

    private void processEvent(JsonNode node, StreamListener listener) {
        String type = node.has("type") ? node.get("type").asText() : "";
        switch (type) {
            case "message_start":
                listener.onMessageStart(toMap(node));
                break;
            case "content_block_start":
                listener.onContentBlockStart(toMap(node));
                break;
            case "content_block_delta":
                listener.onContentBlockDelta(toMap(node));
                break;
            case "content_block_stop":
                listener.onContentBlockStop(toMap(node));
                break;
            case "message_delta":
                listener.onMessageDelta(toMap(node));
                break;
            case "message_stop":
                listener.onMessageStop(toMap(node));
                break;
            case "error":
                listener.onError(new AnthropicApiException(500, "api_error", node.toString()));
                break;
        }
    }

    private Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> map = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isTextual()) map.put(e.getKey(), v.asText());
            else if (v.isInt()) map.put(e.getKey(), v.asInt());
            else if (v.isLong()) map.put(e.getKey(), v.asLong());
            else if (v.isBoolean()) map.put(e.getKey(), v.asBoolean());
            else if (v.isDouble()) map.put(e.getKey(), v.asDouble());
            else if (v.isObject()) map.put(e.getKey(), toMap(v));
            else if (v.isArray()) {
                List<Object> arr = new ArrayList<>();
                for (JsonNode item : v) {
                    if (item.isTextual()) arr.add(item.asText());
                    else if (item.isInt()) arr.add(item.asInt());
                    else if (item.isObject()) arr.add(toMap(item));
                    else arr.add(item.toString());
                }
                map.put(e.getKey(), arr);
            } else map.put(e.getKey(), v.toString());
        }
        return map;
    }

    private String parseErrorType(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node != null && node.has("error")) {
                JsonNode error = node.get("error");
                if (error.has("type")) return error.get("type").asText();
            }
        } catch (Exception ignored) {}
        if (body.contains("429") || body.contains("rate")) return "rate_limit_error";
        if (body.contains("529") || body.contains("overload")) return "overloaded_error";
        return "api_error";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

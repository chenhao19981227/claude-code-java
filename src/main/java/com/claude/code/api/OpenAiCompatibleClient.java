package com.claude.code.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.BufferedSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class OpenAiCompatibleClient implements ApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String apiPath;
    private final String defaultModel;
    private final ApiProvider provider;
    private final RetryHandler retryHandler;

    public OpenAiCompatibleClient(String apiKey, String defaultModel, ApiProvider provider) {
        this(apiKey, defaultModel, provider, null);
    }

    public OpenAiCompatibleClient(String apiKey, String defaultModel, ApiProvider provider, String baseUrl) {
        this.apiKey = apiKey;
        this.defaultModel = defaultModel != null ? defaultModel : "glm-4";
        this.provider = provider;
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            this.baseUrl = baseUrl.trim();
        } else {
            String resolved = resolveBaseUrl(
                provider != null ? provider.getValue() : "glm",
                defaultModel);
            this.baseUrl = resolved != null ? resolved
                : (provider != null ? provider.getDefaultBaseUrl() : "https://open.bigmodel.cn/api/paas");
        }
        this.apiPath = resolveApiPath(this.baseUrl, provider);
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        this.retryHandler = new RetryHandler(3, 1000, 30000, 0.5);
    }

    @Override
    public void streamMessage(StreamRequest request, StreamListener listener) {
        try {
            String body = buildRequestBody(request);
            Request httpRequest = new Request.Builder()
                .url(baseUrl + apiPath)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(body, JSON))
                .build();

            httpClient.newCall(httpRequest).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    listener.onError(e);
                    listener.onComplete();
                }
                @Override public void onResponse(Call call, Response response) {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            String errorBody = responseBody != null ? responseBody.string() : "Unknown error";
                            listener.onError(new AnthropicApiException(response.code(), parseErrorType(errorBody), errorBody));
                            return;
                        }
                        parseSSE(responseBody.source(), listener);
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

    @Override
    public Map<String, Object> sendMessageSync(StreamRequest request) throws Exception {
        return retryHandler.execute(() -> {
            String body = buildRequestBody(request);
            Request httpRequest = new Request.Builder()
                .url(baseUrl + apiPath)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();

            Response response = httpClient.newCall(httpRequest).execute();
            String respBody = response.body().string();
            if (!response.isSuccessful()) {
                throw new AnthropicApiException(response.code(), parseErrorType(respBody), respBody);
            }
            return MAPPER.readValue(respBody, Map.class);
        });
    }

    @Override
    public ApiProvider getProvider() { return provider; }

    public String getBaseUrl() { return baseUrl; }

    // ---- Request body building ----

    private String buildRequestBody(StreamRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escape(request.getModel() != null ? request.getModel() : defaultModel)).append("\",");
        sb.append("\"max_tokens\":").append(request.getMaxTokens()).append(",");
        sb.append("\"stream\":").append(request.isStream()).append(",");
        if (request.getTemperature() != null) {
            sb.append("\"temperature\":").append(request.getTemperature()).append(",");
        }

        sb.append("\"messages\":[");
        boolean first = true;

        if (request.getSystemPrompt() != null) {
            for (String prompt : request.getSystemPrompt()) {
                if (!prompt.isEmpty()) {
                    if (!first) sb.append(",");
                    sb.append("{\"role\":\"system\",\"content\":\"").append(escape(prompt)).append("\"}");
                    first = false;
                }
            }
        }

        List<Map<String, Object>> messages = request.getMessages();
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                String built = buildOpenAiMessage(msg);
                if (built.startsWith("[")) {
                    String inner = built.substring(1, built.length() - 1);
                    if (!inner.isEmpty()) {
                        if (!first) sb.append(",");
                        sb.append(inner);
                        first = false;
                    }
                } else {
                    if (!first) sb.append(",");
                    sb.append(built);
                    first = false;
                }
            }
        }
        sb.append("],");

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            sb.append("\"tools\":[");
            for (int i = 0; i < request.getTools().size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> tool = request.getTools().get(i);
                sb.append("{\"type\":\"function\",\"function\":{");
                sb.append("\"name\":\"").append(escape(String.valueOf(tool.get("name")))).append("\",");
                sb.append("\"description\":\"").append(escape(String.valueOf(tool.get("description")))).append("\",");
                Object inputSchema = tool.get("input_schema");
                if (inputSchema instanceof Map) {
                    sb.append("\"parameters\":").append(mapToJson((Map<String, Object>) inputSchema));
                } else {
                    sb.append("\"parameters\":{}");
                }
                sb.append("}}");
            }
            sb.append("],");
        }

        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }

    // ---- Message format conversion (Anthropic internal -> OpenAI) ----

    @SuppressWarnings("unchecked")
    private String buildOpenAiMessage(Map<String, Object> msg) {
        String role = String.valueOf(msg.get("role"));
        Object content = msg.get("content");

        // 1. Assistant message with tool_calls already set (from round-trip)
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
        if (toolCalls != null && !toolCalls.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"role\":\"assistant\",\"tool_calls\":[");
            for (int i = 0; i < toolCalls.size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> tc = toolCalls.get(i);
                sb.append("{\"id\":\"").append(escape(String.valueOf(tc.get("id")))).append("\",");
                sb.append("\"type\":\"function\",");
                sb.append("\"function\":{\"name\":\"").append(escape(String.valueOf(tc.get("name")))).append("\",");
                sb.append("\"arguments\":");
                Object args = tc.get("arguments");
                if (args instanceof String) sb.append("\"").append(escape((String) args)).append("\"");
                else if (args instanceof Map) sb.append(mapToJson((Map<String, Object>) args));
                else sb.append("\"{}\"");
                sb.append("}}");
            }
            sb.append("],\"content\":null}");
            return sb.toString();
        }

        // 2. Assistant message with tool_use content blocks -> convert to tool_calls
        if ("assistant".equals(role) && content instanceof List) {
            List<?> blocks = (List<?>) content;
            List<Map<String, Object>> toolUseBlocks = new ArrayList<>();
            StringBuilder textContent = new StringBuilder();
            for (Object b : blocks) {
                if (b instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) b;
                    String type = String.valueOf(m.get("type"));
                    if ("tool_use".equals(type)) toolUseBlocks.add(m);
                    else if ("text".equals(type)) {
                        if (textContent.length() > 0) textContent.append("\n");
                        textContent.append(String.valueOf(m.get("text")));
                    }
                }
            }
            if (!toolUseBlocks.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"role\":\"assistant\",\"tool_calls\":[");
                for (int i = 0; i < toolUseBlocks.size(); i++) {
                    if (i > 0) sb.append(",");
                    Map<String, Object> tb = toolUseBlocks.get(i);
                    sb.append("{\"id\":\"").append(escape(String.valueOf(tb.get("id")))).append("\",");
                    sb.append("\"type\":\"function\",");
                    sb.append("\"function\":{\"name\":\"").append(escape(String.valueOf(tb.get("name")))).append("\",");
                    sb.append("\"arguments\":");
                    Object input = tb.get("input");
                    if (input instanceof Map) {
                        sb.append("\"").append(escape(mapToJson((Map<String, Object>) input))).append("\"");
                    } else if (input instanceof String) {
                        sb.append("\"").append(escape((String) input)).append("\"");
                    } else {
                        sb.append("\"{}\"");
                    }
                    sb.append("}}");
                }
                sb.append("],");
                sb.append("\"content\":");
                if (textContent.length() > 0) sb.append("\"").append(escape(textContent.toString())).append("\"");
                else sb.append("null");
                sb.append("}");
                return sb.toString();
            }
        }

        // 3. User message with tool_result content blocks -> convert to role="tool" messages
        if ("user".equals(role) && content instanceof List) {
            List<?> blocks = (List<?>) content;
            boolean hasToolResult = false;
            for (Object b : blocks) {
                if (b instanceof Map && "tool_result".equals(((Map<String, Object>) b).get("type"))) {
                    hasToolResult = true;
                    break;
                }
            }
            if (hasToolResult) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object block : blocks) {
                    if (!(block instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) block;
                    if ("tool_result".equals(m.get("type"))) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"role\":\"tool\",");
                        sb.append("\"tool_call_id\":\"").append(escape(String.valueOf(m.get("tool_use_id")))).append("\",");
                        sb.append("\"content\":\"").append(escape(String.valueOf(m.get("content")))).append("\"}");
                    }
                }
                sb.append("]");
                return sb.toString();
            }
        }

        // 4. Regular message
        StringBuilder sb = new StringBuilder();
        sb.append("{\"role\":\"").append(role).append("\"");
        if (content instanceof String) {
            sb.append(",\"content\":\"").append(escape((String) content)).append("\"");
        } else if (content instanceof List) {
            sb.append(",\"content\":\"\"");
        } else {
            sb.append(",\"content\":\"\"");
        }
        sb.append("}");
        return sb.toString();
    }

    // ---- SSE parsing ----

    private void parseSSE(BufferedSource source, StreamListener listener) throws IOException {
        StringBuilder dataBuffer = new StringBuilder();
        while (!source.exhausted()) {
            String line = source.readUtf8Line();
            if (line == null) break;
            if (line.isEmpty()) {
                if (dataBuffer.length() > 0) {
                    String data = dataBuffer.toString();
                    dataBuffer.setLength(0);
                    if ("[DONE]".equals(data.trim())) break;
                    try {
                        JsonNode node = MAPPER.readTree(data);
                        if (node != null) processEvent(node, listener);
                    } catch (Exception e) {
                        System.err.println("[SSE] Parse error: " + e.getMessage());
                    }
                }
            } else if (line.startsWith("data: ")) {
                if (dataBuffer.length() > 0) dataBuffer.append("\n");
                dataBuffer.append(line.substring(6));
            }
        }
    }

    private void processEvent(JsonNode node, StreamListener listener) {
        try {
            JsonNode choices = node.get("choices");
            if (choices == null || !choices.isArray() || choices.size() == 0) return;
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isNull()
                ? choice.get("finish_reason").asText() : null;

            if (delta == null) return;

            // Reasoning content (thinking process, e.g. GLM-5-turbo reasoning_content)
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                listener.onReasoningDelta(delta.get("reasoning_content").asText());
            }

            // Text content delta
            if (delta.has("content") && !delta.get("content").isNull()) {
                Map<String, Object> data = new HashMap<>();
                data.put("type", "content_block_delta");
                data.put("index", 0);
                Map<String, Object> deltaMap = new HashMap<>();
                deltaMap.put("type", "text_delta");
                deltaMap.put("text", delta.get("content").asText());
                data.put("delta", deltaMap);
                listener.onContentBlockDelta(data);
            }

            // Tool calls
            if (delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
                for (JsonNode tc : delta.get("tool_calls")) {
                    int index = tc.has("index") ? tc.get("index").asInt() : 0;

                    if (tc.has("id")) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("type", "content_block_start");
                        data.put("index", index);
                        Map<String, Object> block = new HashMap<>();
                        block.put("type", "tool_use");
                        block.put("id", tc.get("id").asText());
                        if (tc.has("function")) {
                            block.put("name", tc.get("function").get("name").asText());
                        }
                        data.put("content_block", block);
                        listener.onContentBlockStart(data);
                    }

                    if (tc.has("function") && tc.get("function").has("arguments")) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("type", "content_block_delta");
                        data.put("index", index);
                        Map<String, Object> deltaMap = new HashMap<>();
                        deltaMap.put("type", "input_json_delta");
                        deltaMap.put("partial_json", tc.get("function").get("arguments").asText());
                        data.put("delta", deltaMap);
                        listener.onContentBlockDelta(data);
                    }
                }
            }

            if ("stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
                listener.onMessageStop(new HashMap<String, Object>());
            }
        } catch (Exception e) {
            System.err.println("[SSE] Event error: " + e.getMessage());
        }
    }

    // ---- Helpers ----

    private String resolveApiPath(String url, ApiProvider prov) {
        String lower = url.toLowerCase();
        if (lower.contains("bigmodel") || lower.contains("zhipu") || prov == ApiProvider.GLM) {
            // GLM Coding Plan models (glm-5 series) use /api/coding/paas/v4
            // GLM-4 and others use /api/paas/v4
            // Default to coding endpoint since it supports all models
            return "/v4/chat/completions";
        }
        return "/v1/chat/completions";
    }

    private String resolveBaseUrl(String prov, String model) {
        if (model != null && model.startsWith("glm-5")) {
            // GLM-5 series requires the coding endpoint
            return "https://open.bigmodel.cn/api/coding/paas";
        }
        String lower = prov.toLowerCase();
        if (lower.contains("bigmodel") || lower.contains("zhipu") || "glm".equals(lower)) {
            return "https://open.bigmodel.cn/api/paas";
        }
        return null; // use the configured baseUrl
    }

    private String parseErrorType(String body) {
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node != null && node.has("error")) {
                JsonNode error = node.get("error");
                if (error.has("type")) return error.get("type").asText();
                if (error.has("code")) return error.get("code").asText();
            }
        } catch (Exception ignored) {}
        return "api_error";
    }

    @SuppressWarnings("unchecked")
    private String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":");
            sb.append(valueToJson(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private String valueToJson(Object v) {
        if (v instanceof String) return "\"" + escape((String) v) + "\"";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Map) return mapToJson((Map<String, Object>) v);
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<?> list = (List<?>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(valueToJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"\"";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}

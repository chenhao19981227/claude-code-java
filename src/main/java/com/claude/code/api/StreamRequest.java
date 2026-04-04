package com.claude.code.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StreamRequest {
    private String model;
    private int maxTokens = 8192;
    private List<String> systemPrompt = new ArrayList<>();
    private List<Map<String, Object>> messages = new ArrayList<>();
    private List<Map<String, Object>> tools = new ArrayList<>();
    private boolean stream = true;
    private Double temperature;

    public static class Builder {
        private final StreamRequest req = new StreamRequest();

        public Builder model(String model) { req.model = model; return this; }
        public Builder maxTokens(int maxTokens) { req.maxTokens = maxTokens; return this; }
        public Builder addSystemPrompt(String prompt) { req.systemPrompt.add(prompt); return this; }
        public Builder systemPrompt(List<String> prompts) { req.systemPrompt = prompts; return this; }
        public Builder addMessage(Map<String, Object> message) { req.messages.add(message); return this; }
        public Builder setMessages(List<Map<String, Object>> messages) { req.messages = messages; return this; }
        public Builder messages(List<Map<String, Object>> messages) { req.messages = messages; return this; }
        public Builder addTool(Map<String, Object> tool) { req.tools.add(tool); return this; }
        public Builder setTools(List<Map<String, Object>> tools) { req.tools = tools; return this; }
        public Builder tools(List<Map<String, Object>> tools) { req.tools = tools; return this; }
        public Builder stream(boolean stream) { req.stream = stream; return this; }
        public Builder temperature(double temperature) { req.temperature = temperature; return this; }
        public StreamRequest build() { return req; }
    }

    public String getModel() { return model; }
    public int getMaxTokens() { return maxTokens; }
    public List<String> getSystemPrompt() { return systemPrompt; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public List<Map<String, Object>> getTools() { return tools; }
    public boolean isStream() { return stream; }
    public Double getTemperature() { return temperature; }
}

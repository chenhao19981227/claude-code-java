package com.claude.code.config;

import com.claude.code.mcp.McpServerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "claude")
public class AppProperties {

    private String provider = "glm";
    private String apiKey = "";
    private String baseUrl = "";
    private String mainModel = "glm-5-turbo";
    private String smallModel = "glm-5";
    private int maxTokens = 65535;
    private double temperature = 0.7;
    private int contextWindowSize = 128000;
    private double compactionThreshold = 0.80;
    private int maxToolOutputLength = 32000;
    private int compactionKeepRecentMessages = 8;
    private List<String> skillDirectories = new ArrayList<>(List.of("skills/"));
    private List<Map<String, Object>> mcpServers = new ArrayList<>();
    private String webDir = "web/";

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getMainModel() { return mainModel; }
    public void setMainModel(String mainModel) { this.mainModel = mainModel; }

    public String getSmallModel() { return smallModel; }
    public void setSmallModel(String smallModel) { this.smallModel = smallModel; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getContextWindowSize() { return contextWindowSize; }
    public void setContextWindowSize(int contextWindowSize) { this.contextWindowSize = contextWindowSize; }

    public double getCompactionThreshold() { return compactionThreshold; }
    public void setCompactionThreshold(double compactionThreshold) { this.compactionThreshold = compactionThreshold; }

    public int getMaxToolOutputLength() { return maxToolOutputLength; }
    public void setMaxToolOutputLength(int maxToolOutputLength) { this.maxToolOutputLength = maxToolOutputLength; }

    public int getCompactionKeepRecentMessages() { return compactionKeepRecentMessages; }
    public void setCompactionKeepRecentMessages(int compactionKeepRecentMessages) { this.compactionKeepRecentMessages = compactionKeepRecentMessages; }

    public List<String> getSkillDirectories() { return skillDirectories; }
    public void setSkillDirectories(List<String> skillDirectories) { this.skillDirectories = skillDirectories; }

    public List<Map<String, Object>> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<Map<String, Object>> mcpServers) { this.mcpServers = mcpServers; }

    public String getWebDir() { return webDir; }
    public void setWebDir(String webDir) { this.webDir = webDir; }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String getEffectiveModel() {
        if (mainModel != null && !mainModel.trim().isEmpty()) {
            return mainModel.trim();
        }
        return getDefaultModel(provider);
    }

    public String getResolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            var url = baseUrl.trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return getDefaultBaseUrl(provider, mainModel);
    }

    @SuppressWarnings("unchecked")
    public List<McpServerConfig> getMcpServerConfigs() {
        var configs = new ArrayList<McpServerConfig>();
        if (mcpServers == null) return configs;
        for (var server : mcpServers) {
            String name = server.containsKey("name") ? String.valueOf(server.get("name")) : "unnamed";
            String command = server.containsKey("command") ? String.valueOf(server.get("command")) : "";
            String[] args = new String[0];
            if (server.containsKey("args") && server.get("args") instanceof List<?> argList) {
                args = new String[argList.size()];
                for (int i = 0; i < argList.size(); i++) {
                    args[i] = String.valueOf(argList.get(i));
                }
            }
            var config = new McpServerConfig(name, command, args);
            if (server.containsKey("env") && server.get("env") instanceof Map<?, ?> envMap) {
                var env = new HashMap<String, String>();
                for (var entry : envMap.entrySet()) {
                    env.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                config.setEnv(env);
            }
            configs.add(config);
        }
        return configs;
    }

    private static String getDefaultBaseUrl(String provider, String model) {
        // GLM-5 series requires the coding endpoint
        if (model != null && model.startsWith("glm-5")) {
            return "https://open.bigmodel.cn/api/coding/paas";
        }
        if (provider == null) return "https://open.bigmodel.cn/api/paas";
        return switch (provider.toLowerCase()) {
            case "anthropic" -> "https://api.anthropic.com";
            case "deepseek" -> "https://api.deepseek.com";
            case "openai" -> "https://api.openai.com";
            default -> "https://open.bigmodel.cn/api/paas";
        };
    }

    private static String getDefaultModel(String provider) {
        if (provider == null) return "glm-5-turbo";
        return switch (provider.toLowerCase()) {
            case "anthropic" -> "claude-sonnet-4-20250514";
            case "deepseek" -> "deepseek-chat";
            case "openai" -> "gpt-4o";
            default -> "glm-5-turbo";
        };
    }
}

package com.claude.code.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Settings {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private String provider = "glm";
    private String apiKey = "";
    private String baseUrl = "";
    private String mainModel = "glm-4";
    private String smallModel = "glm-4-flash";
    private int maxTokens = 8192;
    private double temperature = 0.7;
    private List<String> customInstructions = new ArrayList<>();
    private String systemPrompt;
    private boolean autoCompact;

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

    public List<String> getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(List<String> customInstructions) { this.customInstructions = customInstructions; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public boolean isAutoCompact() { return autoCompact; }
    public void setAutoCompact(boolean autoCompact) { this.autoCompact = autoCompact; }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @JsonIgnore
    public String getResolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            String url = baseUrl.trim();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return getDefaultBaseUrl(provider);
    }

    private static String getDefaultBaseUrl(String provider) {
        if (provider == null) return "https://open.bigmodel.cn/api/paas";
        switch (provider.toLowerCase()) {
            case "anthropic": return "https://api.anthropic.com";
            case "glm":       return "https://open.bigmodel.cn/api/paas";
            case "deepseek":  return "https://api.deepseek.com";
            case "openai":    return "https://api.openai.com";
            default:          return "https://open.bigmodel.cn/api/paas";
        }
    }

    private static String getDefaultModel(String provider) {
        if (provider == null) return "glm-4";
        switch (provider.toLowerCase()) {
            case "anthropic": return "claude-sonnet-4-20250514";
            case "glm":       return "glm-4";
            case "deepseek":  return "deepseek-chat";
            case "openai":    return "gpt-4o";
            default:          return "glm-4";
        }
    }

    public static String getConfigDir() {
        String home = System.getProperty("user.home");
        return home + File.separator + ".claude";
    }

    public static String getConfigPath() {
        return getConfigDir() + File.separator + "config.json";
    }

    public static Settings load() {
        File file = new File(getConfigPath());
        if (!file.exists() || !file.canRead()) {
            return createDefault();
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Settings s = MAPPER.readValue(content, Settings.class);
            if (!s.hasApiKey()) {
                return createDefault();
            }
            return s;
        } catch (IOException e) {
            return createDefault();
        }
    }

    private static Settings createDefault() {
        Settings s = new Settings();
        s.save();
        return s;
    }

    public void save() {
        try {
            File dir = new File(getConfigDir());
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String json = MAPPER.writeValueAsString(this);
            Files.write(Paths.get(getConfigPath()), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Warning: failed to save config: " + e.getMessage());
        }
    }

    @JsonIgnore
    public String getEffectiveModel() {
        if (mainModel != null && !mainModel.trim().isEmpty()) {
            return mainModel.trim();
        }
        return getDefaultModel(provider);
    }
}

package com.claude.code.state;

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

    private String mainModel = "claude-sonnet-4-20250514";
    private String smallModel = "claude-3-5-haiku-20241022";
    private List<String> customInstructions = new ArrayList<>();
    private Map<String, Object> permissions = new HashMap<>();
    private Map<String, String> apiKeys = new HashMap<>();
    private boolean autoCompact;
    private int maxTokens = 8192;
    private String systemPrompt;

    public String getMainModel() { return mainModel; }
    public void setMainModel(String mainModel) { this.mainModel = mainModel; }

    public String getSmallModel() { return smallModel; }
    public void setSmallModel(String smallModel) { this.smallModel = smallModel; }

    public List<String> getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(List<String> customInstructions) { this.customInstructions = customInstructions; }

    public Map<String, Object> getPermissions() { return permissions; }
    public void setPermissions(Map<String, Object> permissions) { this.permissions = permissions; }

    public Map<String, String> getApiKeys() { return apiKeys; }
    public void setApiKeys(Map<String, String> apiKeys) { this.apiKeys = apiKeys; }

    public boolean isAutoCompact() { return autoCompact; }
    public void setAutoCompact(boolean autoCompact) { this.autoCompact = autoCompact; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public static String getConfigDir() {
        String home = System.getProperty("user.home");
        return home + File.separator + ".claude";
    }

    public static String getSettingsPath() {
        return getConfigDir() + File.separator + "settings.json";
    }

    public static Settings load() {
        File file = new File(getSettingsPath());
        if (!file.exists() || !file.canRead()) {
            return new Settings();
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return MAPPER.readValue(content, Settings.class);
        } catch (IOException e) {
            return new Settings();
        }
    }

    public void save() {
        try {
            File dir = new File(getConfigDir());
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String json = MAPPER.writeValueAsString(this);
            Files.write(Paths.get(getSettingsPath()), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Warning: failed to save settings: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Map<String, Object> map;
        try {
            String json = MAPPER.writeValueAsString(this);
            map = MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (defaultValue != null && defaultValue.getClass().isInstance(value)) {
            return (T) value;
        }
        return defaultValue;
    }

    public void set(String key, Object value) {
        if ("mainModel".equals(key) && value instanceof String) {
            this.mainModel = (String) value;
        } else if ("smallModel".equals(key) && value instanceof String) {
            this.smallModel = (String) value;
        } else if ("maxTokens".equals(key) && value instanceof Number) {
            this.maxTokens = ((Number) value).intValue();
        } else if ("autoCompact".equals(key) && value instanceof Boolean) {
            this.autoCompact = (Boolean) value;
        } else if ("systemPrompt".equals(key) && value instanceof String) {
            this.systemPrompt = (String) value;
        }
    }
}

package com.claude.code.state;

import com.claude.code.mcp.McpServerConfig;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private List<String> skillDirectories = new ArrayList<String>();
    private List<Map<String, Object>> mcpServers = new ArrayList<Map<String, Object>>();
    private String sessionsDir = "sessions";

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

    public List<String> getSkillDirectories() { return skillDirectories; }
    public void setSkillDirectories(List<String> skillDirectories) { this.skillDirectories = skillDirectories; }

    public List<Map<String, Object>> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<Map<String, Object>> mcpServers) { this.mcpServers = mcpServers; }

    public String getSessionsDir() { return sessionsDir; }
    public void setSessionsDir(String sessionsDir) { this.sessionsDir = sessionsDir; }

    public List<McpServerConfig> getMcpServerConfigs() {
        List<McpServerConfig> configs = new ArrayList<McpServerConfig>();
        if (mcpServers == null) return configs;
        for (Map<String, Object> server : mcpServers) {
            String name = server.containsKey("name") ? String.valueOf(server.get("name")) : "unnamed";
            String command = server.containsKey("command") ? String.valueOf(server.get("command")) : "";
            String[] args = new String[0];
            if (server.containsKey("args") && server.get("args") instanceof List) {
                List<?> argList = (List<?>) server.get("args");
                args = new String[argList.size()];
                for (int i = 0; i < argList.size(); i++) {
                    args[i] = String.valueOf(argList.get(i));
                }
            }
            McpServerConfig config = new McpServerConfig(name, command, args);
            if (server.containsKey("env") && server.get("env") instanceof Map) {
                Map<String, String> env = new HashMap<String, String>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) server.get("env")).entrySet()) {
                    env.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
                config.setEnv(env);
            }
            configs.add(config);
        }
        return configs;
    }

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

    public static String getProjectConfigPath() {
        return "config.yaml";
    }

    @JsonIgnore
    public static String getConfigDir() {
        String home = System.getProperty("user.home");
        return home + File.separator + ".claude";
    }

    @JsonIgnore
    public static String getLegacyConfigPath() {
        return getConfigDir() + File.separator + "config.json";
    }

    public static Settings load() {
        // Priority: project config.yaml > legacy ~/.claude/config.json > defaults
        File projectConfig = new File(getProjectConfigPath());
        if (projectConfig.exists() && projectConfig.canRead()) {
            try {
                Settings s = loadYaml(projectConfig);
                if (s != null) return s;
            } catch (Exception e) {
                System.err.println("Warning: failed to read config.yaml: " + e.getMessage());
            }
        }

        File legacyConfig = new File(getLegacyConfigPath());
        if (legacyConfig.exists() && legacyConfig.canRead()) {
            try {
                String content = new String(Files.readAllBytes(legacyConfig.toPath()), StandardCharsets.UTF_8);
                Settings s = MAPPER.readValue(content, Settings.class);
                if (s.hasApiKey()) {
                    // Migrate: create project config.yaml from legacy config
                    s.save();
                    return s;
                }
            } catch (IOException e) {
                // ignore
            }
        }

        return createDefault();
    }

    @SuppressWarnings("unchecked")
    private static Settings loadYaml(File file) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> map;
        try (InputStream is = new FileInputStream(file)) {
            map = yaml.load(is);
        }
        if (map == null) return null;

        Settings s = new Settings();
        if (map.containsKey("provider"))    s.provider    = String.valueOf(map.get("provider"));
        if (map.containsKey("apiKey"))      s.apiKey      = String.valueOf(map.get("apiKey"));
        if (map.containsKey("baseUrl"))     s.baseUrl     = String.valueOf(map.get("baseUrl"));
        if (map.containsKey("mainModel"))   s.mainModel   = String.valueOf(map.get("mainModel"));
        if (map.containsKey("smallModel"))  s.smallModel  = String.valueOf(map.get("smallModel"));
        if (map.containsKey("maxTokens"))   s.maxTokens   = ((Number) map.get("maxTokens")).intValue();
        if (map.containsKey("temperature")) s.temperature = ((Number) map.get("temperature")).doubleValue();
        if (map.containsKey("systemPrompt")) s.systemPrompt = String.valueOf(map.get("systemPrompt"));
        if (map.containsKey("autoCompact")) s.autoCompact = Boolean.TRUE.equals(map.get("autoCompact"));
        if (map.containsKey("customInstructions")) {
            Object ci = map.get("customInstructions");
            if (ci instanceof List) {
                s.customInstructions = new ArrayList<>();
                for (Object item : (List<?>) ci) {
                    s.customInstructions.add(String.valueOf(item));
                }
            }
        }
        if (map.containsKey("skillDirectories")) {
            Object sd = map.get("skillDirectories");
            if (sd instanceof List) {
                s.skillDirectories = new ArrayList<>();
                for (Object item : (List<?>) sd) {
                    s.skillDirectories.add(String.valueOf(item));
                }
            }
        }
        if (map.containsKey("mcpServers")) {
            Object ms = map.get("mcpServers");
            if (ms instanceof List) {
                s.mcpServers = new ArrayList<>();
                for (Object item : (List<?>) ms) {
                    if (item instanceof Map) {
                        s.mcpServers.add((Map<String, Object>) item);
                    }
                }
            }
        }
        if (map.containsKey("sessionsDir")) {
            s.sessionsDir = String.valueOf(map.get("sessionsDir"));
        }
        return s;
    }

    private static Settings createDefault() {
        Settings s = new Settings();
        // Migrate from legacy config if exists
        File legacyConfig = new File(getLegacyConfigPath());
        if (legacyConfig.exists() && legacyConfig.canRead()) {
            try {
                String content = new String(Files.readAllBytes(legacyConfig.toPath()), StandardCharsets.UTF_8);
                Settings legacy = MAPPER.readValue(content, Settings.class);
                s.provider = legacy.provider;
                s.apiKey = legacy.apiKey;
                s.baseUrl = legacy.baseUrl;
                s.mainModel = legacy.mainModel;
                s.smallModel = legacy.smallModel;
                s.maxTokens = legacy.maxTokens;
                s.temperature = legacy.temperature;
                s.systemPrompt = legacy.systemPrompt;
                s.autoCompact = legacy.autoCompact;
                s.customInstructions = legacy.customInstructions;
            } catch (IOException e) {
                // ignore
            }
        }
        s.save();
        return s;
    }

    public void save() {
        try {
            String yaml = toYaml();
            Files.write(Paths.get(getProjectConfigPath()), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("Warning: failed to save config: " + e.getMessage());
        }
    }

    private String toYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Claude Code Java Configuration\n");
        sb.append("#\n");
        sb.append("# provider: glm | deepseek | openai | anthropic\n");
        sb.append("# apiKey: your API key\n");
        sb.append("# baseUrl: custom API base URL (optional, auto-detected from provider)\n");
        sb.append("# mainModel: model name for main loop\n");
        sb.append("# smallModel: model for small tasks\n\n");
        sb.append("provider: ").append(provider != null ? provider : "glm").append("\n");
        sb.append("apiKey: ").append(apiKey != null ? apiKey : "").append("\n");
        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            sb.append("baseUrl: ").append(baseUrl).append("\n");
        }
        sb.append("mainModel: ").append(mainModel != null ? mainModel : "glm-4").append("\n");
        sb.append("smallModel: ").append(smallModel != null ? smallModel : "glm-4-flash").append("\n");
        sb.append("maxTokens: ").append(maxTokens).append("\n");
        sb.append("temperature: ").append(temperature).append("\n");
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            sb.append("systemPrompt: \"").append(systemPrompt.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        if (autoCompact) {
            sb.append("autoCompact: true\n");
        }
        if (customInstructions != null && !customInstructions.isEmpty()) {
            sb.append("\ncustomInstructions:\n");
            for (String ci : customInstructions) {
                sb.append("  - ").append(ci).append("\n");
            }
        }
        if (skillDirectories != null && !skillDirectories.isEmpty()) {
            sb.append("\nskillDirectories:\n");
            for (String dir : skillDirectories) {
                sb.append("  - ").append(dir).append("\n");
            }
        }
        if (mcpServers != null && !mcpServers.isEmpty()) {
            sb.append("\nmcpServers:\n");
            for (Map<String, Object> server : mcpServers) {
                sb.append("  - name: ").append(server.getOrDefault("name", "")).append("\n");
                sb.append("    command: ").append(server.getOrDefault("command", "")).append("\n");
                if (server.containsKey("args") && server.get("args") instanceof List) {
                    sb.append("    args:\n");
                    for (Object arg : (List<?>) server.get("args")) {
                        sb.append("      - ").append(arg).append("\n");
                    }
                }
                if (server.containsKey("env") && server.get("env") instanceof Map) {
                    sb.append("    env:\n");
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) server.get("env")).entrySet()) {
                        sb.append("      ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                    }
                }
            }
        }
        if (sessionsDir != null && !"sessions".equals(sessionsDir)) {
            sb.append("\nsessionsDir: ").append(sessionsDir).append("\n");
        }
        return sb.toString();
    }

    @JsonIgnore
    public String getEffectiveModel() {
        if (mainModel != null && !mainModel.trim().isEmpty()) {
            return mainModel.trim();
        }
        return getDefaultModel(provider);
    }
}

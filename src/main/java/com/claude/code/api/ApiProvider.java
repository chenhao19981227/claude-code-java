package com.claude.code.api;

public enum ApiProvider {
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com"),
    OPENAI("openai", "OpenAI Compatible", "https://api.openai.com"),
    GLM("glm", "GLM (ZhipuAI)", "https://open.bigmodel.cn/api/paas"),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com"),
    CUSTOM("custom", "Custom Provider", "");

    private final String value;
    private final String title;
    private final String defaultBaseUrl;

    ApiProvider(String value, String title, String defaultBaseUrl) {
        this.value = value;
        this.title = title;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String getValue() { return value; }
    public String getTitle() { return title; }
    public String getDefaultBaseUrl() { return defaultBaseUrl; }

    public static ApiProvider fromString(String value) {
        if (value == null || value.isEmpty()) return AUTO_DETECT;
        for (ApiProvider p : values()) {
            if (p.value.equalsIgnoreCase(value)) return p;
        }
        return CUSTOM;
    }

    public static final ApiProvider AUTO_DETECT = null;

    public static ApiProvider detectFromBaseUrl(String baseUrl) {
        if (baseUrl == null) return ANTHROPIC;
        String lower = baseUrl.toLowerCase();
        if (lower.contains("anthropic")) return ANTHROPIC;
        if (lower.contains("bigmodel") || lower.contains("glm") || lower.contains("zhipu")) return GLM;
        if (lower.contains("deepseek")) return DEEPSEEK;
        if (lower.contains("openai")) return OPENAI;
        return CUSTOM;
    }

    public boolean isAnthropic() { return this == ANTHROPIC; }
    public boolean isOpenAiCompatible() { return this != ANTHROPIC; }
}

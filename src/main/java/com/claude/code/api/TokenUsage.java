package com.claude.code.api;

public class TokenUsage {
    private final int inputTokens;
    private final int outputTokens;
    private final int cacheReadInputTokens;
    private final int cacheCreationInputTokens;

    public TokenUsage(int inputTokens, int outputTokens, int cacheReadInputTokens, int cacheCreationInputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
        this.cacheCreationInputTokens = cacheCreationInputTokens;
    }

    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getCacheReadInputTokens() { return cacheReadInputTokens; }
    public int getCacheCreationInputTokens() { return cacheCreationInputTokens; }
    public int totalTokens() { return inputTokens + outputTokens; }
}

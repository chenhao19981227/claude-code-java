package com.claude.code.api;

public record TokenUsage(int inputTokens, int outputTokens, int cacheReadInputTokens, int cacheCreationInputTokens) {
    public int totalTokens() { return inputTokens + outputTokens; }
}

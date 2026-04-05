package com.claude.code.api;

public class AnthropicApiException extends Exception {
    private final int statusCode;
    private final String errorType;

    public AnthropicApiException(int statusCode, String errorType, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorType = errorType;
    }

    public int getStatusCode() { return statusCode; }
    public String getErrorType() { return errorType; }
    public boolean isRateLimit() { return statusCode == 429; }
    public boolean isOverloaded() { return statusCode == 529; }
    public boolean isPromptTooLong() { return "prompt_too_long".equals(errorType); }
    public boolean isAuthError() { return statusCode == 401 || statusCode == 403; }
}

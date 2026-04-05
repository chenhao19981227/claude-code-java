package com.claude.code.permission;

public enum PermissionMode {
    DEFAULT("default", "Default", ""),
    PLAN("plan", "Plan Mode", "\u23F8"),
    ACCEPT_EDITS("acceptEdits", "Accept Edits", "\u25B6\u25B6"),
    BYPASS_PERMISSIONS("bypassPermissions", "Bypass", "\u25B6\u25B6"),
    DONT_ASK("dontAsk", "Don't Ask", "\u25B6\u25B6"),
    AUTO("auto", "Auto", "\u25B6\u25B6");

    private final String value;
    private final String title;
    private final String symbol;

    PermissionMode(String value, String title, String symbol) {
        this.value = value;
        this.title = title;
        this.symbol = symbol;
    }

    public String getValue() { return value; }
    public String getTitle() { return title; }
    public String getSymbol() { return symbol; }

    public static PermissionMode fromString(String value) {
        if (value == null) return DEFAULT;
        for (var mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) return mode;
        }
        return DEFAULT;
    }

    public boolean isDefault() { return this == DEFAULT; }
    public boolean isBypass() { return this == BYPASS_PERMISSIONS; }
    public boolean isPlan() { return this == PLAN; }
}

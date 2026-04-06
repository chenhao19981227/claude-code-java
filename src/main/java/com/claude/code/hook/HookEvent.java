package com.claude.code.hook;

public enum HookEvent {
    SESSION_START("SessionStart"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    POST_TOOL_USE_FAILURE("PostToolUseFailure"),
    STOP("Stop"),
    NOTIFICATION("Notification");

    private final String eventName;

    HookEvent(String eventName) { this.eventName = eventName; }
    public String getEventName() { return eventName; }

    public static HookEvent fromName(String name) {
        for (var e : values()) {
            if (e.eventName.equals(name)) return e;
        }
        return null;
    }
}

package com.claude.code.hook;

public class HookResult {
    private final boolean allowed;
    private final String reason;
    private final String output;

    private HookResult(boolean allowed, String reason, String output) {
        this.allowed = allowed;
        this.reason = reason;
        this.output = output;
    }

    public static HookResult allow() { return new HookResult(true, null, ""); }
    public static HookResult allow(String output) { return new HookResult(true, null, output != null ? output : ""); }
    public static HookResult deny(String reason) { return new HookResult(false, reason, ""); }

    public boolean isAllowed() { return allowed; }
    public String getReason() { return reason; }
    public String getOutput() { return output; }
}

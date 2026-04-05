package com.claude.code.permission;

import java.util.List;
import java.util.Map;

public class PermissionResult {
    public enum Behavior { ALLOW, ASK, DENY, PASSTHROUGH }

    private final Behavior behavior;
    private String message;
    private String updatedInput;
    private PermissionDecisionReason decisionReason;

    private PermissionResult(Behavior behavior) { this.behavior = behavior; }

    public Behavior getBehavior() { return behavior; }
    public String getMessage() { return message; }
    public String getUpdatedInput() { return updatedInput; }
    public PermissionDecisionReason getDecisionReason() { return decisionReason; }

    public boolean isAllow() { return behavior == Behavior.ALLOW; }
    public boolean isAsk() { return behavior == Behavior.ASK; }
    public boolean isDeny() { return behavior == Behavior.DENY; }

    public static PermissionResult allow() { return new PermissionResult(Behavior.ALLOW); }

    public static PermissionResult allow(String updatedInput) {
        var r = new PermissionResult(Behavior.ALLOW);
        r.updatedInput = updatedInput;
        return r;
    }

    public static PermissionResult allow(String updatedInput, PermissionDecisionReason reason) {
        var r = new PermissionResult(Behavior.ALLOW);
        r.updatedInput = updatedInput;
        r.decisionReason = reason;
        return r;
    }

    public static PermissionResult ask(String message) {
        var r = new PermissionResult(Behavior.ASK);
        r.message = message;
        return r;
    }

    public static PermissionResult ask(String message, PermissionDecisionReason reason) {
        var r = new PermissionResult(Behavior.ASK);
        r.message = message;
        r.decisionReason = reason;
        return r;
    }

    public static PermissionResult deny(String message) {
        var r = new PermissionResult(Behavior.DENY);
        r.message = message;
        return r;
    }

    public static PermissionResult deny(String message, PermissionDecisionReason reason) {
        var r = new PermissionResult(Behavior.DENY);
        r.message = message;
        r.decisionReason = reason;
        return r;
    }

    public static PermissionResult passthrough(String message) {
        var r = new PermissionResult(Behavior.PASSTHROUGH);
        r.message = message;
        return r;
    }
}

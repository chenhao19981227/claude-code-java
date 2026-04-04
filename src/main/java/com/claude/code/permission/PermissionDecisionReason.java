package com.claude.code.permission;

public class PermissionDecisionReason {
    public enum ReasonType { RULE, MODE, CLASSIFIER, SAFETY_CHECK, WORKING_DIR, OTHER }

    private final ReasonType type;
    private final String reason;

    public PermissionDecisionReason(ReasonType type, String reason) {
        this.type = type;
        this.reason = reason;
    }

    public ReasonType getType() { return type; }
    public String getReason() { return reason; }

    public static PermissionDecisionReason rule(String rule) { return new PermissionDecisionReason(ReasonType.RULE, rule); }
    public static PermissionDecisionReason mode(String mode) { return new PermissionDecisionReason(ReasonType.MODE, mode); }
    public static PermissionDecisionReason classifier(String reason) { return new PermissionDecisionReason(ReasonType.CLASSIFIER, reason); }
    public static PermissionDecisionReason safetyCheck(String reason) { return new PermissionDecisionReason(ReasonType.SAFETY_CHECK, reason); }
    public static PermissionDecisionReason workingDir(String reason) { return new PermissionDecisionReason(ReasonType.WORKING_DIR, reason); }
    public static PermissionDecisionReason other(String reason) { return new PermissionDecisionReason(ReasonType.OTHER, reason); }
}

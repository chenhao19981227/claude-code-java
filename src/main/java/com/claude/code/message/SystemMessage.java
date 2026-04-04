package com.claude.code.message;

public class SystemMessage extends Message {
    public enum SubType {
        API_ERROR, INFORMATIONAL, LOCAL_COMMAND, COMPACT_BOUNDARY,
        TURN_DURATION, MEMORY_SAVED, STOP_HOOK_SUMMARY,
        BRIDGE_STATUS, AGENTS_KILLED, PERMISSION_RETRY
    }

    private final SubType subType;
    private String content;

    public SystemMessage(SubType subType, String content) {
        super(Type.SYSTEM);
        this.subType = subType;
        this.content = content;
    }

    public SubType getSubType() { return subType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}

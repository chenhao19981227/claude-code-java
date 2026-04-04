package com.claude.code.message;

import java.util.UUID;

public abstract class Message {
    public enum Type { USER, ASSISTANT, SYSTEM, PROGRESS }

    private final String id;
    private final long timestamp;
    private final Type type;

    protected Message(Type type) {
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        this.timestamp = System.currentTimeMillis();
        this.type = type;
    }

    public String getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public Type getType() { return type; }

    public static String deriveShortId(String uuid) {
        if (uuid == null || uuid.length() < 6) return uuid;
        return uuid.substring(0, 6);
    }
}

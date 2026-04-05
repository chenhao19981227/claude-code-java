package com.claude.code.session;

import java.util.ArrayList;
import java.util.List;

public class Session {
    private String id;
    private String title;
    private long createdAt;
    private long updatedAt;
    private List<SessionMessage> messages = new ArrayList<SessionMessage>();

    // Default constructor for Jackson
    public Session() {}

    public Session(String id, String title) {
        this.id = id;
        this.title = title;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; this.updatedAt = System.currentTimeMillis(); }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public List<SessionMessage> getMessages() { return messages; }
    public void setMessages(List<SessionMessage> messages) { this.messages = messages; this.updatedAt = System.currentTimeMillis(); }

    public void addMessage(SessionMessage msg) {
        this.messages.add(msg);
        this.updatedAt = System.currentTimeMillis();
    }

    // Generate a short ID
    public static String generateId() {
        return String.valueOf(System.currentTimeMillis());
    }
}

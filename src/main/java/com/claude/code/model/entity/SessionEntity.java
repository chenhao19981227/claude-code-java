package com.claude.code.model.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String title;

    private long createdAt;
    private long updatedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("timestamp ASC")
    private List<SessionMessageEntity> messages = new ArrayList<>();

    public SessionEntity() {}

    public SessionEntity(String sessionId, String title) {
        this.sessionId = sessionId;
        this.title = title != null ? title : "New Session";
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) {
        this.title = title;
        this.updatedAt = System.currentTimeMillis();
    }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public List<SessionMessageEntity> getMessages() { return messages; }
    public void setMessages(List<SessionMessageEntity> messages) {
        this.messages = messages;
        this.updatedAt = System.currentTimeMillis();
    }

    public void addMessage(SessionMessageEntity msg) {
        this.messages.add(msg);
        msg.setSession(this);
        this.updatedAt = System.currentTimeMillis();
    }

    public static String generateId() {
        return String.valueOf(System.currentTimeMillis());
    }
}

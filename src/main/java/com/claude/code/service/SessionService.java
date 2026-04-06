package com.claude.code.service;

import com.claude.code.model.entity.SessionEntity;
import com.claude.code.model.entity.SessionMessageEntity;
import com.claude.code.repository.SessionMessageRepository;
import com.claude.code.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;

    public SessionService(SessionRepository sessionRepository, SessionMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public SessionEntity createSession(String title) {
        var entity = new SessionEntity(SessionEntity.generateId(), title != null ? title : "New Session");
        return sessionRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public SessionEntity loadSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId).orElse(null);
    }

    @Transactional(readOnly = true)
    public SessionEntity loadSessionWithMessages(String sessionId) {
        var session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null) {
            // Force-initialize lazy-loaded messages while transaction is open
            session.getMessages().size();
        }
        return session;
    }

    @Transactional(readOnly = true)
    public List<SessionEntity> listSessions() {
        return sessionRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSessionsSummary() {
        var sessions = sessionRepository.findAllByOrderByUpdatedAtDesc();
        var result = new ArrayList<Map<String, Object>>();
        for (var s : sessions) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", s.getSessionId());
            m.put("title", s.getTitle());
            m.put("createdAt", s.getCreatedAt());
            m.put("updatedAt", s.getUpdatedAt());
            result.add(m);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<SessionMessageEntity> loadMessages(String sessionId) {
        return messageRepository.findBySessionSessionIdOrderByTimestampAsc(sessionId);
    }

    @Transactional
    public void addMessage(String sessionId, String role, String content) {
        var session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        var msg = new SessionMessageEntity(role, content);
        session.addMessage(msg);
        sessionRepository.save(session);
    }

    @Transactional
    public void addMessage(String sessionId, String role, String content, String reasoning) {
        var session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        var msg = new SessionMessageEntity(role, content);
        msg.setReasoning(reasoning);
        session.addMessage(msg);
        sessionRepository.save(session);
    }

    @Transactional
    public void addFullMessage(String sessionId, String role, String content, String reasoning,
                                String messagePayload, int inputTokens, int outputTokens) {
        var session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        var msg = new SessionMessageEntity(role, content);
        msg.setReasoning(reasoning);
        msg.setMessagePayload(messagePayload);
        msg.setInputTokens(inputTokens);
        msg.setOutputTokens(outputTokens);
        session.addMessage(msg);
        sessionRepository.save(session);
    }

    @Transactional
    public void renameSession(String sessionId, String title) {
        var session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session == null) return;
        session.setTitle(title);
        sessionRepository.save(session);
    }

    @Transactional
    public void deleteSession(String sessionId) {
        messageRepository.deleteBySessionSessionId(sessionId);
        sessionRepository.deleteBySessionId(sessionId);
    }

    @Transactional
    public void saveSession(SessionEntity session) {
        sessionRepository.save(session);
    }
}

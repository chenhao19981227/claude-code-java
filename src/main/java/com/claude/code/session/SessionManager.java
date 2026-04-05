package com.claude.code.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class SessionManager {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final File sessionsDir;
    private final ReentrantLock lock = new ReentrantLock();

    public SessionManager(String basePath) {
        this.sessionsDir = new File(basePath, "sessions");
        if (!sessionsDir.exists()) {
            sessionsDir.mkdirs();
        }
    }

    public SessionManager() {
        this(System.getProperty("user.dir"));
    }

    /**
     * List all sessions sorted by updatedAt desc, with full message content.
     */
    public List<Session> listSessions() {
        lock.lock();
        try {
            File[] files = sessionsDir.listFiles();
            if (files == null) return Collections.emptyList();

            List<Session> sessions = new ArrayList<>();
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".json")) {
                    try {
                        String content = new String(Files.readAllBytes(file.toPath()), "UTF-8");
                        Session session = MAPPER.readValue(content, Session.class);
                        sessions.add(session);
                    } catch (Exception e) {
                        System.err.println("[SessionManager] Corrupted session file: " + file.getName() + " - " + e.getMessage());
                    }
                }
            }

            Collections.sort(sessions, new Comparator<Session>() {
                @Override
                public int compare(Session a, Session b) {
                    return Long.compare(b.getUpdatedAt(), a.getUpdatedAt());
                }
            });
            return sessions;
        } finally {
            lock.unlock();
        }
    }

    /**
     * List sessions as summary (metadata only, no messages) sorted by updatedAt desc.
     */
    public List<Session> listSessionsSummary() {
        List<Session> sessions = listSessions();
        for (Session s : sessions) {
            s.setMessages(new ArrayList<SessionMessage>());
        }
        return sessions;
    }

    /**
     * Create a new empty session.
     */
    public Session createSession(String title) {
        lock.lock();
        try {
            String id = Session.generateId();
            Session session = new Session(id, title != null ? title : "New Session");
            saveSession(session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Load a session by ID with full messages.
     */
    public Session loadSession(String id) {
        lock.lock();
        try {
            File file = getSessionFile(id);
            if (!file.exists()) return null;
            String content = new String(Files.readAllBytes(file.toPath()), "UTF-8");
            return MAPPER.readValue(content, Session.class);
        } catch (Exception e) {
            System.err.println("[SessionManager] Failed to load session " + id + ": " + e.getMessage());
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Save a session to disk.
     */
    public void saveSession(Session session) {
        lock.lock();
        try {
            File file = getSessionFile(session.getId());
            session.setUpdatedAt(System.currentTimeMillis());
            MAPPER.writeValue(file, session);
        } catch (IOException e) {
            System.err.println("[SessionManager] Failed to save session " + session.getId() + ": " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delete a session by ID.
     */
    public void deleteSession(String id) {
        lock.lock();
        try {
            File file = getSessionFile(id);
            if (file.exists()) {
                file.delete();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get existing session or create a new one.
     */
    public Session getOrCreateCurrent(String sessionId) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            Session existing = loadSession(sessionId);
            if (existing != null) return existing;
        }
        return createSession("New Session");
    }

    private File getSessionFile(String id) {
        return new File(sessionsDir, id + ".json");
    }
}

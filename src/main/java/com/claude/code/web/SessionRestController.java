package com.claude.code.web;

import com.claude.code.model.entity.SessionEntity;
import com.claude.code.query.QueryEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionRestController {

    private final QueryEngine queryEngine;
    private final ObjectMapper objectMapper;

    public SessionRestController(QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
        this.objectMapper = new ObjectMapper();
    }

    @GetMapping
    public List<Map<String, Object>> listSessions() {
        return queryEngine.listSessions();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, Object> body) {
        String title = "New Session";
        if (body != null && body.get("title") != null) {
            title = String.valueOf(body.get("title"));
        }
        var session = queryEngine.newSession();
        session.setTitle(title);
        var result = new HashMap<String, Object>();
        result.put("id", session.getSessionId());
        result.put("title", session.getTitle());
        result.put("createdAt", session.getCreatedAt());
        result.put("updatedAt", session.getUpdatedAt());
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        var session = queryEngine.loadSession(id);
        if (session == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
        }
        var result = new HashMap<String, Object>();
        result.put("id", session.getSessionId());
        result.put("title", session.getTitle());
        result.put("createdAt", session.getCreatedAt());
        result.put("updatedAt", session.getUpdatedAt());
        var messages = new java.util.ArrayList<Map<String, Object>>();
        for (var sm : session.getMessages()) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("role", sm.getRole());
            m.put("content", sm.getContent());
            if (sm.getReasoning() != null && !sm.getReasoning().isEmpty()) {
                m.put("reasoning", sm.getReasoning());
            }
            messages.add(m);
        }
        result.put("messages", messages);
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String id) {
        queryEngine.deleteSession(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> renameSession(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String title = body != null ? (String) body.get("title") : null;
        if (title == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }
        boolean success = queryEngine.renameSession(id, title);
        if (success) {
            return ResponseEntity.ok(Map.of("id", id, "title", title));
        }
        return ResponseEntity.status(404).body(Map.of("error", "Session not found"));
    }
}

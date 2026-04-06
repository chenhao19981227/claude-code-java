package com.claude.code.hook;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON input sent to hook processes via stdin.
 * Matches Claude Code's hook input format.
 */
public class HookInput {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String sessionId;
    private final String cwd;
    private final String hookEventName;
    private final String toolName;
    private final Map<String, Object> toolInput;

    public HookInput(String sessionId, String cwd, String hookEventName,
                     String toolName, Map<String, Object> toolInput) {
        this.sessionId = sessionId;
        this.cwd = cwd;
        this.hookEventName = hookEventName;
        this.toolName = toolName;
        this.toolInput = toolInput;
    }

    public String toJson() {
        try {
            var map = new LinkedHashMap<String, Object>();
            map.put("session_id", sessionId);
            map.put("cwd", cwd);
            map.put("hook_event_name", hookEventName);
            if (toolName != null) map.put("tool_name", toolName);
            if (toolInput != null) map.put("tool_input", toolInput);
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}

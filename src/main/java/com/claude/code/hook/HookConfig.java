package com.claude.code.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * Loads and manages hook definitions from application.yml configuration.
 *
 * Expected config in application.yml:
 * app:
 *   hooks:
 *     PreToolUse:
 *       - matcher: "Bash"
 *         type: command
 *         command: "/path/to/check.sh"
 *         timeout: 30
 *     PostToolUse:
 *       - matcher: "Edit|Write"
 *         type: command
 *         command: "/path/to/format.sh"
 */
@Component
public class HookConfig {
    private static final Logger log = LoggerFactory.getLogger(HookConfig.class);

    private final Map<String, ?> rawHooks;
    private volatile List<HookDefinition> allHooks;

    @SuppressWarnings("unchecked")
    public HookConfig(org.springframework.core.env.Environment env) {
        // Read from claude.hooks in application.yml
        Map<String, Object> claudeProps = env.getProperty("claude", Map.class);
        this.rawHooks = (claudeProps != null) ? (Map<String, ?>) claudeProps.get("hooks") : Map.of();
    }

    @PostConstruct
    public void init() {
        var hooks = new ArrayList<HookDefinition>();
        parseHooks(rawHooks, hooks);
        allHooks = List.copyOf(hooks);
        log.info("Loaded {} hook definitions", allHooks.size());
        for (var h : allHooks) {
            log.info("  {}", h);
        }
    }

    @SuppressWarnings("unchecked")
    private void parseHooks(Map<String, ?> raw, List<HookDefinition> result) {
        if (raw == null) return;
        for (var entry : raw.entrySet()) {
            String event = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof List<?> list) {
                for (var item : list) {
                    if (item instanceof Map<?, ?> map) {
                        try {
                            var def = new HookDefinition(
                                    event,
                                    (String) map.get("matcher"),
                                    (String) map.get("type"),
                                    (String) map.get("command"),
                                    map.containsKey("timeout") ? ((Number) map.get("timeout")).intValue() : 30);
                            // Validate matcher regex
                            def.matchesTool("test"); // will throw if invalid
                            result.add(def);
                        } catch (PatternSyntaxException e) {
                            log.warn("Invalid hook matcher '{}': {}", map.get("matcher"), e.getMessage());
                        } catch (Exception e) {
                            log.warn("Invalid hook definition for event '{}': {}", event, e.getMessage());
                        }
                    }
                }
            }
        }
    }

    /** Get all hooks matching a specific event and tool name */
    public List<HookDefinition> getHooksForEvent(String eventName, String toolName) {
        if (allHooks == null) return List.of();
        return allHooks.stream()
                .filter(h -> h.getEvent().equals(eventName) && h.matchesTool(toolName))
                .toList();
    }

    public List<HookDefinition> getAllHooks() {
        return allHooks != null ? allHooks : List.of();
    }
}

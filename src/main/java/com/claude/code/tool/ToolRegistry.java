package com.claude.code.tool;

import com.claude.code.state.AppState;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Component
public class ToolRegistry {
    private final List<Tool> tools = new ArrayList<>();

    public void register(Tool tool) {
        for (var existing : tools) {
            if (existing.getName().equals(tool.getName())) {
                return;
            }
        }
        tools.add(tool);
    }

    public void unregister(String name) {
        tools.removeIf(t -> t.getName().equals(name));
    }

    public Tool findByName(String name) {
        if (name == null) return null;
        for (var tool : tools) {
            if (tool.getName().equals(name)) return tool;
        }
        return null;
    }

    public List<Tool> getAllTools() { return Collections.unmodifiableList(tools); }

    public List<Tool> getEnabledTools() {
        return tools.stream().filter(Tool::isEnabled).toList();
    }

    public List<String> getToolNames() {
        return tools.stream()
                .filter(Tool::isEnabled)
                .map(Tool::getName)
                .sorted()
                .toList();
    }

    public String getToolsDescription() {
        var sb = new StringBuilder();
        var enabled = tools.stream()
                .filter(Tool::isEnabled)
                .sorted(Comparator.comparing(Tool::getName))
                .toList();
        for (var tool : enabled) {
            sb.append("- **").append(tool.getName()).append("**: ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }
}

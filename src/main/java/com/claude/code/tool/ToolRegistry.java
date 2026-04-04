package com.claude.code.tool;

import com.claude.code.state.AppState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ToolRegistry {
    private final List<Tool> tools = new ArrayList<>();

    public void register(Tool tool) {
        for (Tool existing : tools) {
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
        for (Tool tool : tools) {
            if (tool.getName().equals(name)) return tool;
        }
        return null;
    }

    public List<Tool> getAllTools() { return Collections.unmodifiableList(tools); }

    public List<Tool> getEnabledTools(AppState state) {
        List<Tool> result = new ArrayList<>();
        for (Tool tool : tools) {
            if (tool.isEnabled()) result.add(tool);
        }
        return result;
    }

    public List<Tool> getEnabledTools() {
        List<Tool> result = new ArrayList<>();
        for (Tool tool : tools) {
            if (tool.isEnabled()) result.add(tool);
        }
        return result;
    }

    public List<String> getToolNames() {
        List<String> names = new ArrayList<>();
        for (Tool tool : tools) {
            if (tool.isEnabled()) names.add(tool.getName());
        }
        Collections.sort(names);
        return names;
    }

    public String getToolsDescription() {
        StringBuilder sb = new StringBuilder();
        List<Tool> enabled = getEnabledTools();
        Collections.sort(enabled, Comparator.comparing(Tool::getName));
        for (Tool tool : enabled) {
            sb.append("- **").append(tool.getName()).append("**: ").append(tool.getDescription()).append("\n");
        }
        return sb.toString();
    }
}

package com.claude.code.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TodoWriteTool extends Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TodoWriteTool() {
        super("TodoWrite", "Write and manage a todo list for tracking progress on tasks.",
              "manage todo list", 10000, false, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = MAPPER.readValue(inputJson, Map.class);
        Object todos = input.get("todos");
        if (todos == null) return ToolResult.error("todos is required");

        if (context.getAppState() != null) {
            context.getAppState().setSetting("todos", todos.toString());
        }

        var data = new HashMap<String, Object>();
        data.put("status", "updated");
        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return """
                {"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","completed"]}}}}},"required":["todos"]}""";
    }
}

package com.claude.code.tool;

import com.claude.code.util.JsonParse;

import java.util.HashMap;
import java.util.Map;

public class TodoWriteTool extends Tool {
    public TodoWriteTool() {
        super("TodoWrite", "Write and manage a todo list for tracking progress on tasks.",
              "manage todo list", 10000, false, false);
    }

    @Override
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = JsonParse.simpleParse(inputJson);
        Object todos = input.get("todos");
        if (todos == null) return ToolResult.error("todos is required");

        // Store todos in app state
        if (context.getAppState() != null) {
            context.getAppState().setSetting("todos", todos.toString());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("status", "updated");
        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{\"todos\":{\"type\":\"array\",\"items\":{\"type\":\"object\"," +
               "\"properties\":{\"content\":{\"type\":\"string\"},\"status\":{\"type\":\"string\",\"enum\":[\"pending\",\"in_progress\",\"completed\"]}}}}}," +
               "\"required\":[\"todos\"]}";
    }
}

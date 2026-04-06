package com.claude.code.tool;

import com.claude.code.permission.PermissionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

@Component
public class WriteTool extends Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WriteTool() {
        super("Write", "Create a new file with the given content. Does NOT overwrite existing files — use Edit tool for that.",
              "create new files", 500000, false, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = MAPPER.readValue(inputJson, Map.class);
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");

        if (filePath == null) return ToolResult.error("file_path is required");
        if (content == null) return ToolResult.error("content is required");

        File file = new File(filePath);
        if (!file.isAbsolute()) file = new File(context.getWorkingDirectory(), filePath);

        if (file.exists()) {
            return ToolResult.error("File already exists: " + filePath + ". Use the Edit tool to modify existing files.");
        }

        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            Files.createDirectories(parentDir.toPath());
        }

        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));

        var data = new HashMap<String, Object>();
        data.put("filePath", file.getAbsolutePath());
        data.put("size", file.length());
        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return """
                {"type":"object","properties":{"file_path":{"type":"string","description":"Absolute path to the new file"},"content":{"type":"string","description":"Content to write to the file"}},"required":["file_path","content"]}""";
    }

    @Override
    @SuppressWarnings("unchecked")
    public PermissionResult checkPermissions(String inputJson, ToolUseContext context) {
        try {
            Map<String, Object> input = MAPPER.readValue(inputJson, Map.class);
            String filePath = (String) input.get("file_path");
            if (filePath != null && context.getPermissionChecker() != null) {
                File file = new File(filePath);
                if (!file.isAbsolute()) file = new File(context.getWorkingDirectory(), filePath);
                return context.getPermissionChecker().checkPermission("Write", inputJson, file.getAbsolutePath());
            }
        } catch (Exception ignored) {}
        return PermissionResult.ask("Allow creating file?");
    }
}

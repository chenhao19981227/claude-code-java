package com.claude.code.tool;

import com.claude.code.permission.PermissionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

@Component
public class FileEditTool extends Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public FileEditTool() {
        super("Edit", "Make targeted edits to a file. Replace an exact string match with new content.",
              "modify file contents in place", 100000, false, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = MAPPER.readValue(inputJson, Map.class);
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(input.get("replace_all"));

        if (filePath == null) return ToolResult.error("file_path is required");
        if (oldString == null) return ToolResult.error("old_string is required");
        if (newString == null) return ToolResult.error("new_string is required");
        if (oldString.equals(newString)) return ToolResult.error("old_string and new_string must be different");

        File file = new File(filePath);
        if (!file.isAbsolute()) file = new File(context.getWorkingDirectory(), filePath);
        if (!file.exists()) return ToolResult.error("File not found: " + filePath);
        if (!file.canRead()) return ToolResult.error("Cannot read file: " + filePath);

        String content = Files.readString(file.toPath());

        int index = content.indexOf(oldString);
        if (index < 0) {
            return ToolResult.error("old_string not found in file. The file may have been modified since it was last read.");
        }

        int secondIndex = content.indexOf(oldString, index + oldString.length());
        if (secondIndex >= 0 && !replaceAll) {
            return ToolResult.error("old_string appears multiple times. Use replace_all=true to replace all occurrences.");
        }

        String newContent = replaceAll ? content.replace(oldString, newString)
                : content.substring(0, index) + newString + content.substring(index + oldString.length());

        Files.write(file.toPath(), newContent.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);

        var data = new HashMap<String, Object>();
        data.put("filePath", file.getAbsolutePath());
        data.put("oldString", oldString);
        data.put("newString", newString);
        data.put("replaceAll", replaceAll);
        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return """
                {"type":"object","properties":{"file_path":{"type":"string"},"old_string":{"type":"string","description":"The exact string to replace"},"new_string":{"type":"string","description":"The replacement string"},"replace_all":{"type":"boolean","description":"Replace all occurrences"}},"required":["file_path","old_string","new_string"]}""";
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
                return context.getPermissionChecker().checkPermission("Edit", inputJson, file.getAbsolutePath());
            }
        } catch (Exception ignored) {}
        return PermissionResult.ask("Allow editing file?");
    }
}

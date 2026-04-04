package com.claude.code.tool;

import com.claude.code.permission.PermissionResult;
import com.claude.code.util.JsonParse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileReadTool extends Tool {
    public FileReadTool() {
        super("Read", "Read the contents of a file. Use filePath for the absolute path. Use offset and limit for pagination.",
              "read files, images, PDFs, notebooks", Integer.MAX_VALUE, true, true);
    }

    @Override
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = JsonParse.simpleParse(inputJson);
        String filePath = (String) input.get("file_path");
        if (filePath == null) return ToolResult.error("file_path is required");

        File file = resolvePath(filePath, context);
        if (!file.exists()) return ToolResult.error("File not found: " + filePath);
        if (!file.isFile()) return ToolResult.error("Not a file: " + filePath);
        if (!file.canRead()) return ToolResult.error("Cannot read file: " + filePath);

        long maxSize = 5 * 1024 * 1024; // 5MB
        if (file.length() > maxSize) return ToolResult.error("File too large: " + file.length() + " bytes (max " + maxSize + ")");

        int offset = input.containsKey("offset") ? ((Number) input.get("offset")).intValue() : 0;
        int limit = input.containsKey("limit") ? ((Number) input.get("limit")).intValue() : Integer.MAX_VALUE;

        List<String> allLines = Files.readAllLines(Paths.get(file.getAbsolutePath()), StandardCharsets.UTF_8);
        int totalLines = allLines.size();

        if (offset >= totalLines) offset = Math.max(0, totalLines - 1);
        int endLine = Math.min(offset + limit, totalLines);

        List<String> selectedLines = allLines.subList(offset, endLine);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < selectedLines.size(); i++) {
            content.append(selectedLines.get(i)).append("\n");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("type", "text");
        Map<String, Object> fileInfo = new HashMap<>();
        fileInfo.put("filePath", file.getAbsolutePath());
        fileInfo.put("content", content.toString());
        fileInfo.put("numLines", selectedLines.size());
        fileInfo.put("startLine", offset + 1);
        fileInfo.put("totalLines", totalLines);
        data.put("file", fileInfo);

        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{\"file_path\":{\"type\":\"string\",\"description\":\"Absolute path to the file\"}," +
               "\"offset\":{\"type\":\"number\",\"description\":\"Line number to start reading from (1-based)\"}," +
               "\"limit\":{\"type\":\"number\",\"description\":\"Maximum number of lines to read\"}}," +
               "\"required\":[\"file_path\"]}";
    }

    @Override
    public PermissionResult checkPermissions(String inputJson, ToolUseContext context) {
        Map<String, Object> input = JsonParse.simpleParse(inputJson);
        String filePath = (String) input.get("file_path");
        if (filePath != null && context.getPermissionChecker() != null) {
            File file = resolvePath(filePath, context);
            return context.getPermissionChecker().checkPermission("Read", inputJson, file.getAbsolutePath());
        }
        return PermissionResult.allow();
    }

    private File resolvePath(String filePath, ToolUseContext context) {
        File file = new File(filePath);
        if (!file.isAbsolute()) {
            file = new File(context.getWorkingDirectory(), filePath);
        }
        return file;
    }
}

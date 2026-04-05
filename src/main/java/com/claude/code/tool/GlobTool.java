package com.claude.code.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GlobTool extends Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public GlobTool() {
        super("Glob", "Find files by name pattern or wildcard. Returns matching file paths.",
              "find files by name pattern or wildcard", 100000, true, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = MAPPER.readValue(inputJson, Map.class);
        String pattern = (String) input.get("pattern");
        String path = (String) input.get("path");
        if (pattern == null) return ToolResult.error("pattern is required");

        String searchDir = path;
        if (searchDir == null || searchDir.isEmpty()) searchDir = context.getWorkingDirectory();
        var dir = new File(searchDir);
        if (!dir.exists()) return ToolResult.error("Directory not found: " + searchDir);

        var resultFiles = new ArrayList<String>();
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        Files.walkFileTree(dir.toPath(), EnumSet.of(FileVisitOption.FOLLOW_LINKS), 1000, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matcher.matches(file.getFileName())) {
                    resultFiles.add(file.toString().replace("\\", "/"));
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
        });

        boolean truncated = resultFiles.size() > 1000;
        List<String> finalFiles = truncated ? resultFiles.subList(0, 1000) : resultFiles;

        var data = new HashMap<String, Object>();
        data.put("filenames", finalFiles);
        data.put("numFiles", finalFiles.size());
        data.put("truncated", truncated);
        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return """
                {"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern (e.g. '**/*.ts')"},"path":{"type":"string","description":"Root directory to search"}},"required":["pattern"]}""";
    }
}

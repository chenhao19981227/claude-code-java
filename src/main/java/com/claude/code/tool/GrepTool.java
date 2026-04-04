package com.claude.code.tool;

import com.claude.code.util.JsonParse;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GrepTool extends Tool {
    public GrepTool() {
        super("Grep", "Search file contents using regular expressions. Returns matching lines with context.",
              "search file contents with regex", 100000, true, true);
    }

    @Override
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = JsonParse.simpleParse(inputJson);
        String pattern = (String) input.get("pattern");
        String path = (String) input.get("path");
        String outputMode = (String) input.getOrDefault("output_mode", "content");
        String type = (String) input.get("type");
        int headLimit = input.containsKey("head_limit") ? ((Number) input.get("head_limit")).intValue() : 250;

        if (pattern == null) return ToolResult.error("pattern is required");

        String searchDir = path != null ? path : context.getWorkingDirectory();
        List<String> matchingFiles = new ArrayList<>();
        List<String> contentLines = new ArrayList<>();
        int totalMatches = 0;

        try {
            ProcessBuilder pb;
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            List<String> cmd = new ArrayList<>();
            if (isWindows) {
                cmd.add("findstr");
                cmd.add("/S");
                cmd.add("/N");
                cmd.add(pattern);
            } else {
                cmd.add("grep");
                cmd.add("-rn");
                cmd.add("-m");
                cmd.add(String.valueOf(headLimit));
                cmd.add(pattern);
            }
            if (type != null) {
                if (isWindows) cmd.add(type);
                else { cmd.add("--include=" + type); }
            }
            cmd.add(searchDir);

            pb = new ProcessBuilder(cmd);
            pb.directory(new File(context.getWorkingDirectory()));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null && totalMatches < headLimit) {
                contentLines.add(line);
                String filePath = isWindows ? line.substring(0, line.indexOf(':')) : line.substring(0, line.indexOf(':'));
                if (!matchingFiles.contains(filePath)) matchingFiles.add(filePath);
                totalMatches++;
            }
            process.waitFor(30, TimeUnit.SECONDS);

            if (totalMatches >= headLimit) contentLines.add("(truncated at " + headLimit + " matches)");
        } catch (Exception e) {
            return ToolResult.error("Grep failed: " + e.getMessage());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("mode", outputMode);
        data.put("numFiles", matchingFiles.size());
        data.put("filenames", matchingFiles);
        data.put("content", contentLines);
        data.put("numMatches", totalMatches);
        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\",\"description\":\"Regular expression pattern\"}," +
               "\"path\":{\"type\":\"string\",\"description\":\"Directory to search\"}," +
               "\"output_mode\":{\"type\":\"string\",\"enum\":[\"content\",\"files_with_matches\",\"count\"]}," +
               "\"head_limit\":{\"type\":\"number\",\"description\":\"Max matches to return\"}}," +
               "\"required\":[\"pattern\"]}";
    }
}

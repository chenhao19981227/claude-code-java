package com.claude.code.tool;

import com.claude.code.util.JsonParse;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BashTool extends Tool {
    public BashTool() {
        super("Bash", "Execute a shell command in the terminal. Use this for running git, npm, build tools, and other CLI commands.",
              "execute shell commands", 30000, false, false);
    }

    @Override
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = JsonParse.simpleParse(inputJson);
        String command = (String) input.get("command");
        Number timeoutNum = (Number) input.get("timeout");
        long timeoutMs = timeoutNum != null ? timeoutNum.longValue() : 120000;

        if (command == null || command.trim().isEmpty()) {
            return ToolResult.error("command is required");
        }

        ProcessBuilder pb;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            pb = new ProcessBuilder("bash", "-c", command);
        }
        pb.directory(new File(context.getWorkingDirectory()));
        pb.redirectErrorStream(false);

        Process process = pb.start();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread stdoutReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            } catch (Exception ignored) {}
        });
        Thread stderrReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            } catch (Exception ignored) {}
        });
        stdoutReader.start();
        stderrReader.start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.error("Command timed out after " + timeoutMs + "ms");
        }

        stdoutReader.join(5000);
        stderrReader.join(5000);

        int exitCode = process.exitValue();
        Map<String, Object> data = new HashMap<>();
        data.put("stdout", stdout.toString());
        data.put("stderr", stderr.toString());
        data.put("exitCode", exitCode);
        data.put("interrupted", false);

        if (exitCode != 0) {
            return ToolResult.error("Command failed with exit code " + exitCode + "\n" + stderr.toString());
        }

        return ToolResult.success(data);
    }

    @Override
    public String getInputSchemaJson() {
        return "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"The shell command to execute\"}," +
               "\"timeout\":{\"type\":\"number\",\"description\":\"Timeout in milliseconds (default 120000)\"}}," +
                "\"required\":[\"command\"]}";
    }
}

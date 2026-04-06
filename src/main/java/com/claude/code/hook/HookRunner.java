package com.claude.code.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Executes hooks (command type) following Claude Code's execution model:
 * - All matching hooks run in parallel
 * - Input JSON sent via stdin
 * - Exit code 0 = allow, exit code 2 = deny/block
 * - "Most restrictive wins" — if ANY hook denies, the result is deny
 */
@Service
public class HookRunner {
    private static final Logger log = LoggerFactory.getLogger(HookRunner.class);

    private final HookConfig hookConfig;

    public HookRunner(HookConfig hookConfig) {
        this.hookConfig = hookConfig;
    }

    /**
     * Run all hooks matching the given event and tool name.
     * Returns combined result (most restrictive wins).
     */
    public HookResult runHook(String eventName, String toolName, String toolInputJson, String cwd) {
        return runHook(eventName, toolName, toolInputJson, cwd, null);
    }

    /**
     * Run all hooks matching the given event and tool name.
     */
    public HookResult runHook(String eventName, String toolName, String toolInputJson,
                               String cwd, String sessionId) {
        var hooks = hookConfig.getHooksForEvent(eventName, toolName);
        if (hooks.isEmpty()) return HookResult.allow();

        log.debug("Running {} hooks for event={}, tool={}", hooks.size(), eventName, toolName);

        var input = new HookInput(sessionId, cwd, eventName, toolName, null);
        String inputJson = toolInputJson != null ? toolInputJson : input.toJson();

        // Run all matching hooks in parallel
        var results = new ArrayList<HookResult>();
        var latch = new CountDownLatch(hooks.size());

        for (var hook : hooks) {
            Thread.startVirtualThread(() -> {
                try {
                    var result = executeCommand(hook, inputJson);
                    synchronized (results) { results.add(result); }
                } catch (Exception e) {
                    log.warn("Hook execution failed for {}: {}", hook, e.getMessage());
                    synchronized (results) { results.add(HookResult.allow()); }
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for hooks");
        }

        // Most restrictive wins
        HookResult combined = HookResult.allow();
        StringBuilder outputBuffer = new StringBuilder();
        for (var r : results) {
            if (!r.isAllowed()) return r; // deny wins immediately
            if (r.getOutput() != null && !r.getOutput().isEmpty()) {
                if (outputBuffer.length() > 0) outputBuffer.append("\n");
                outputBuffer.append(r.getOutput());
            }
        }
        return outputBuffer.length() > 0 ? HookResult.allow(outputBuffer.toString()) : combined;
    }

    private HookResult executeCommand(HookDefinition hook, String inputJson) {
        log.info("Executing hook: {}", hook);
        long start = System.currentTimeMillis();

        try {
            var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            var pb = new ProcessBuilder(
                    isWindows ? "cmd.exe" : "bash",
                    isWindows ? "/c" : "-c",
                    hook.getCommand()
            );
            pb.directory(new java.io.File("."));
            pb.redirectErrorStream(false);

            var process = pb.start();

            // Write input JSON to stdin
            try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(inputJson);
                writer.flush();
            }

            // Read stdout
            var stdout = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            }

            // Read stderr
            var stderr = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(hook.getTimeout(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Hook timed out after {}s: {}", hook.getTimeout(), hook);
                return HookResult.allow(); // timeout = non-blocking, allow
            }

            int exitCode = process.exitValue();
            long elapsed = System.currentTimeMillis() - start;

            if (exitCode == 2) {
                String reason = stderr.toString().trim();
                log.info("Hook DENIED ({}ms): {} — {}", elapsed, hook, reason);
                return HookResult.deny(reason.isEmpty() ? "Blocked by hook" : reason);
            }

            if (exitCode == 0) {
                String output = stdout.toString().trim();
                log.info("Hook ALLOWED ({}ms): {}", elapsed, hook);
                return HookResult.allow(output);
            }

            // Any other exit code = non-blocking
            log.info("Hook non-blocking exit {} ({}ms): {}", exitCode, elapsed, hook);
            return HookResult.allow();

        } catch (Exception e) {
            log.warn("Hook execution error for {}: {}", hook, e.getMessage());
            return HookResult.allow(); // error = non-blocking
        }
    }
}

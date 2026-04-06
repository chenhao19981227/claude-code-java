package com.claude.code.query;

import com.claude.code.api.*;
import com.claude.code.compaction.ContextCompactor;
import com.claude.code.config.AppProperties;
import com.claude.code.context.SystemPromptBuilder;
import com.claude.code.hook.HookResult;
import com.claude.code.hook.HookRunner;
import com.claude.code.message.*;
import com.claude.code.mcp.McpManager;
import com.claude.code.model.entity.SessionEntity;
import com.claude.code.model.entity.SessionMessageEntity;
import com.claude.code.permission.PermissionChecker;
import com.claude.code.permission.PermissionResult;
import com.claude.code.service.SessionService;
import com.claude.code.skill.Skill;
import com.claude.code.skill.SkillLoader;
import com.claude.code.skill.SkillMatcher;
import com.claude.code.state.AppState;
import com.claude.code.state.Store;
import com.claude.code.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class QueryEngine {
    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TURNS = 50;
    private static final long PERMISSION_TIMEOUT_MS = 120_000;

    private final ApiClient client;
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private final Store<AppState> appStore;
    private final SessionService sessionService;
    private final AppProperties appProperties;
    private final SkillLoader skillLoader;
    private final McpManager mcpManager;
    private final SkillMatcher skillMatcher;
    private final HookRunner hookRunner;
    private final ContextCompactor contextCompactor;
    private final String workingDir;
    private final List<Tool> injectedTools;

    private SessionEntity currentSession;
    private volatile boolean aborted;
    private Map<String, Skill> skillMap;
    private String agentMode = "build";

    // Pending permission requests: requestId -> future to complete with action
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingPermissions = new ConcurrentHashMap<>();

    public interface QueryCallback {
        void onTextDelta(String text);
        void onReasoningDelta(String text);
        void onToolStart(String toolName, String toolUseId, String input);
        void onToolResult(String toolUseId, String result, boolean isError);
        void onPermissionRequest(String requestId, String toolName, String description, String inputPreview);
        void onError(String error);
        void onComplete();
    }

    public QueryEngine(ApiClient client, ToolRegistry toolRegistry,
                       SessionService sessionService, AppProperties appProperties,
                       SkillLoader skillLoader, McpManager mcpManager,
                       HookRunner hookRunner, ContextCompactor contextCompactor,
                       List<Tool> tools) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.sessionService = sessionService;
        this.appProperties = appProperties;
        this.skillLoader = skillLoader;
        this.mcpManager = mcpManager;
        this.hookRunner = hookRunner;
        this.contextCompactor = contextCompactor;
        this.injectedTools = tools != null ? tools : List.of();
        this.workingDir = System.getProperty("user.dir");
        this.permissionChecker = new PermissionChecker(workingDir);
        this.appStore = new Store<>(new AppState(appProperties));
        this.appStore.getState().setCurrentWorkingDirectory(workingDir);
        this.skillMatcher = new SkillMatcher();
    }

    @PostConstruct
    public void init() {
        // Register all injected Tool beans
        for (var tool : injectedTools) {
            toolRegistry.register(tool);
        }
        // Load skills (indexed by name for fast slash-command lookup)
        skillMap = skillLoader.loadAllSkills();
        log.info("Initialized QueryEngine with {} core tools, {} skills: {}",
                toolRegistry.getEnabledTools().size(), skillMap.size(),
                skillMap.values().stream().map(Skill::getName).toList());

        // Register MCP tools asynchronously — don't block startup
        Thread.startVirtualThread(() -> {
            boolean mcpReady = mcpManager.awaitInit(120_000);
            if (mcpReady) {
                var adapters = mcpManager.getToolAdapters();
                for (var adapter : adapters) {
                    toolRegistry.register(adapter);
                }
                log.info("MCP tools registered: {} total tools now: {}",
                        adapters.size(), toolRegistry.getEnabledTools().size());
            } else {
                log.warn("MCP initialization timed out, proceeding without MCP tools");
            }
        });
    }

    // ---- Mode management ----

    public String getAgentMode() { return agentMode; }

    public void setAgentMode(String mode) {
        this.agentMode = mode;
        this.permissionChecker.setAgentMode(mode);
        if ("build".equals(mode)) {
            // Reset "allow all" when switching modes
            this.permissionChecker.resetAllowedAll();
        }
    }

    // ---- Permission response (called from WebSocket handler) ----

    public void respondPermission(String requestId, String action) {
        CompletableFuture<String> future = pendingPermissions.remove(requestId);
        if (future != null) {
            if ("allow_all".equals(action)) {
                // Find which tool this was for and mark it as allowed-all
                // We store the tool name in the future's context - use a wrapper
                future.complete(action);
            } else {
                future.complete(action);
            }
        }
    }

    // ---- Main message submission ----

    public void submitMessage(String userInput, final QueryCallback callback) {
        if (userInput == null || userInput.trim().isEmpty()) return;
        aborted = false;

        if (currentSession == null) {
            String title = userInput.length() > 50 ? userInput.substring(0, 50) : userInput;
            currentSession = sessionService.createSession(title);
        } else if ("New Session".equals(currentSession.getTitle()) || currentSession.getMessages().isEmpty()) {
            String title = userInput.length() > 50 ? userInput.substring(0, 50) : userInput;
            sessionService.renameSession(currentSession.getSessionId(), title);
            currentSession.setTitle(title);
        }

        AppState state = appStore.getState();
        var userMsg = new UserMessage(userInput);
        state.addMessage(userMsg);
        sessionService.addMessage(currentSession.getSessionId(), "user", userInput);

        // Reset compacted flag for each new user message
        state.setCompacted(false);

        // Check for slash command — direct skill invocation (Claude Code style)
        List<Skill> activeSkills = null;

        // /compact command — force context compaction
        if ("/compact".equalsIgnoreCase(userInput.trim())) {
            log.info("Manual /compact command triggered");
            String sessionId = currentSession != null ? currentSession.getSessionId() : null;
            String result = contextCompactor.forceCompact(state, sessionId);
            callback.onTextDelta("Context compaction: " + result + "\n");
            callback.onComplete();
            return;
        }

        Optional<Skill> slashMatch = skillMatcher.matchSlashCommand(userInput, skillMap);
        if (slashMatch.isPresent()) {
            Skill matched = slashMatch.get();
            String arg = matched.extractArgument(userInput);
            log.info("Slash command activated: /{} (arg: {})", matched.getName(), arg);
            activeSkills = List.of(matched);
            // If the command had an argument, use it as the actual user input for the model
            if (!arg.isEmpty()) {
                // Replace the user message with just the argument
                state.getMessages().remove(state.getMessages().size() - 1);
                var argMsg = new UserMessage(arg);
                state.addMessage(argMsg);
                sessionService.addMessage(currentSession.getSessionId(), "user", arg);
                userInput = arg;
            }
        }

        List<String> systemPrompt = SystemPromptBuilder.buildSystemPrompt(
                workingDir, appProperties, activeSkills, toolRegistry, agentMode, skillMatcher, skillMap);

        List<Map<String, Object>> apiMessages = buildApiMessages(state.getMessages());
        List<Map<String, Object>> apiTools = buildApiTools();
        log.info("Submitting query with {} tools: {}", apiTools.size(),
                apiTools.stream().map(t -> t.get("name")).toList());

        var request = new StreamRequest.Builder()
            .model(state.getMainLoopModel())
            .systemPrompt(systemPrompt)
            .messages(apiMessages)
            .tools(apiTools)
            .stream(true)
            .temperature(appProperties.getTemperature())
            .maxTokens(appProperties.getMaxTokens())
            .build();

        executeQueryLoop(request, callback, 0);
    }

    // ---- Query loop ----

    private void executeQueryLoop(StreamRequest request, final QueryCallback callback, int turn) {
        if (turn >= MAX_TURNS || aborted) {
            callback.onComplete();
            return;
        }

        final StringBuilder textBuffer = new StringBuilder();
        final Map<Integer, String> toolUseIds = new HashMap<>();
        final Map<Integer, String> toolNames = new HashMap<>();
        final Map<Integer, StringBuilder> toolInputBuilders = new HashMap<>();
        final List<Map<String, Object>> pendingToolResults = new ArrayList<>();

        client.streamMessage(request, new StreamListener() {
            @Override public void onMessageStart(Map<String, Object> data) {}

            @Override public void onContentBlockStart(Map<String, Object> data) {
                Map<String, Object> block = getMap(data, "content_block");
                if (block == null) return;
                String type = String.valueOf(block.get("type"));
                if ("tool_use".equals(type)) {
                    int index = ((Number) data.get("index")).intValue();
                    toolUseIds.put(index, String.valueOf(block.get("id")));
                    toolNames.put(index, String.valueOf(block.get("name")));
                    toolInputBuilders.put(index, new StringBuilder());
                }
            }

            @Override public void onContentBlockDelta(Map<String, Object> data) {
                Map<String, Object> delta = getMap(data, "delta");
                if (delta == null) return;
                String type = String.valueOf(delta.get("type"));
                if ("text_delta".equals(type)) {
                    String text = String.valueOf(delta.get("text"));
                    textBuffer.append(text);
                    callback.onTextDelta(text);
                } else if ("input_json_delta".equals(type)) {
                    int index = ((Number) data.get("index")).intValue();
                    var inputBuilder = toolInputBuilders.get(index);
                    if (inputBuilder != null) {
                        inputBuilder.append(String.valueOf(delta.get("partial_json")));
                    }
                }
            }

            @Override public void onContentBlockStop(Map<String, Object> data) {
                Object indexObj = data.get("index");
                if (indexObj == null) return;
                int index = ((Number) indexObj).intValue();
                String toolUseId = toolUseIds.get(index);
                String toolName = toolNames.get(index);
                String inputJson = toolInputBuilders.containsKey(index) ? toolInputBuilders.get(index).toString() : "{}";
                if (toolUseId != null && toolName != null) {
                    callback.onToolStart(toolName, toolUseId, inputJson);
                }
            }

            @Override public void onMessageDelta(Map<String, Object> data) {}
            @Override public void onMessageStop(Map<String, Object> data) {}

            @Override public void onReasoningDelta(String text) {
                callback.onReasoningDelta(text);
            }

            @Override public void onTokenUsage(TokenUsage usage) {
                AppState state = appStore.getState();
                state.setLastInputTokens(usage.inputTokens());
                state.addInputTokens(usage.inputTokens());
                state.addOutputTokens(usage.outputTokens());
                log.debug("Token usage: input={}, output={}, total_input={}",
                        usage.inputTokens(), usage.outputTokens(), state.getTotalInputTokens());
            }

            @Override public void onError(Throwable error) {
                callback.onError(error.getMessage());
            }

            @Override public void onComplete() {
                AppState state = appStore.getState();

                if (textBuffer.length() > 0) {
                    var assistantMsg = new AssistantMessage(textBuffer.toString());
                    for (var e : toolUseIds.entrySet()) {
                        assistantMsg.addToolUseBlock(new ToolUseBlock(
                            e.getValue(), toolNames.get(e.getValue()),
                            toolInputBuilders.getOrDefault(e.getKey(), new StringBuilder()).toString()));
                    }
                    state.addMessage(assistantMsg);

                    if (currentSession != null) {
                        // Serialize full message structure including tool_use blocks
                        String payload = serializeAssistantMessage(assistantMsg, null);
                        sessionService.addFullMessage(
                                currentSession.getSessionId(), "assistant", textBuffer.toString(),
                                null, payload, 0, 0);
                    }
                }

                if (!toolUseIds.isEmpty() && !aborted) {
                    executeToolsAndContinue(toolUseIds, toolNames, toolInputBuilders, callback, turn, request, pendingToolResults);
                } else {
                    callback.onComplete();
                }
            }
        });
    }

    private void executeToolsAndContinue(Map<Integer, String> toolUseIds, Map<Integer, String> toolNames,
                                           Map<Integer, StringBuilder> toolInputBuilders,
                                           final QueryCallback callback, int turn, StreamRequest request,
                                           List<Map<String, Object>> pendingToolResults) {
        AppState state = appStore.getState();
        var toolContext = new ToolUseContext();
        toolContext.setWorkingDirectory(workingDir);
        toolContext.setPermissionChecker(permissionChecker);
        toolContext.setAppStore(appStore);
        toolContext.setAllTools(toolRegistry.getEnabledTools());

        // Collect tool results to store in state (critical for conversation continuity)
        var toolResultBlocks = new ArrayList<ToolResultBlock>();

        for (var e : toolUseIds.entrySet()) {
            if (aborted) break;
            int index = e.getKey();
            String toolUseId = e.getValue();
            String toolName = toolNames.get(index);
            String inputJson = toolInputBuilders.containsKey(index) ? toolInputBuilders.get(index).toString() : "{}";

            Tool tool = toolRegistry.findByName(toolName);
            if (tool == null) {
                String err = "Unknown tool: " + toolName;
                callback.onToolResult(toolUseId, err, true);
                pendingToolResults.add(createToolResult(toolUseId, err, true));
                toolResultBlocks.add(new ToolResultBlock(toolUseId, err, true));
                continue;
            }

            // PreToolUse hook — can block tool execution
            HookResult preHook = hookRunner.runHook("PreToolUse", toolName, inputJson, workingDir,
                    currentSession != null ? currentSession.getSessionId() : null);
            if (!preHook.isAllowed()) {
                String err = "Blocked by hook: " + preHook.getReason();
                callback.onToolResult(toolUseId, err, true);
                pendingToolResults.add(createToolResult(toolUseId, err, true));
                toolResultBlocks.add(new ToolResultBlock(toolUseId, err, true));
                continue;
            }

            // Check if already allowed-all for this tool
            if (permissionChecker.isAllowedAll(toolName)) {
                try {
                    ToolResult result = executeToolWithHook(tool, inputJson, toolContext, toolUseId, currentSession != null ? currentSession.getSessionId() : null);
                    String resultJson = truncateToolOutput(result.getDataAsJson());
                    callback.onToolResult(toolUseId, resultJson, result.isError());
                    pendingToolResults.add(createToolResult(toolUseId, resultJson, result.isError()));
                    toolResultBlocks.add(new ToolResultBlock(toolUseId, resultJson, result.isError()));
                } catch (Exception ex) {
                    String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    callback.onToolResult(toolUseId, "Error: " + errorMsg, true);
                    pendingToolResults.add(createToolResult(toolUseId, "Error: " + errorMsg, true));
                    toolResultBlocks.add(new ToolResultBlock(toolUseId, "Error: " + errorMsg, true));
                }
                continue;
            }

            PermissionResult permResult = tool.checkPermissions(inputJson, toolContext);

            if (permResult.isDeny()) {
                String denied = "Denied: " + permResult.getMessage();
                callback.onToolResult(toolUseId, denied, true);
                pendingToolResults.add(createToolResult(toolUseId, denied, true));
                toolResultBlocks.add(new ToolResultBlock(toolUseId, denied, true));
                continue;
            }

            // Auto-approve read-only tools
            if (permResult.isAsk() && tool.isReadOnly()) {
                permResult = PermissionResult.allow();
            }

            // Ask user for permission (async wait)
            if (permResult.isAsk()) {
                String requestId = UUID.randomUUID().toString();
                CompletableFuture<String> future = new CompletableFuture<>();
                pendingPermissions.put(requestId, future);

                // Build a description of what the tool is about to do
                String description = buildPermissionDescription(toolName, inputJson);
                String inputPreview = inputJson.length() > 500 ? inputJson.substring(0, 500) + "..." : inputJson;

                callback.onPermissionRequest(requestId, toolName, description, inputPreview);

                try {
                    String action = future.get(PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    pendingPermissions.remove(requestId);

                    switch (action) {
                        case "allow_once" -> {
                            ToolResult result = executeToolWithHook(tool, inputJson, toolContext, toolUseId, currentSession != null ? currentSession.getSessionId() : null);
                            String resultJson = truncateToolOutput(result.getDataAsJson());
                            callback.onToolResult(toolUseId, resultJson, result.isError());
                            pendingToolResults.add(createToolResult(toolUseId, resultJson, result.isError()));
                            toolResultBlocks.add(new ToolResultBlock(toolUseId, resultJson, result.isError()));
                        }
                        case "allow_all" -> {
                            permissionChecker.allowAllForTool(toolName);
                            ToolResult result = executeToolWithHook(tool, inputJson, toolContext, toolUseId, currentSession != null ? currentSession.getSessionId() : null);
                            String resultJson = truncateToolOutput(result.getDataAsJson());
                            callback.onToolResult(toolUseId, resultJson, result.isError());
                            pendingToolResults.add(createToolResult(toolUseId, resultJson, result.isError()));
                            toolResultBlocks.add(new ToolResultBlock(toolUseId, resultJson, result.isError()));
                        }
                        default -> {
                            // denied
                            callback.onToolResult(toolUseId, "Denied by user", true);
                            pendingToolResults.add(createToolResult(toolUseId, "Denied by user", true));
                            toolResultBlocks.add(new ToolResultBlock(toolUseId, "Denied by user", true));
                        }
                    }
                } catch (TimeoutException te) {
                    pendingPermissions.remove(requestId);
                    String err = "Permission request timed out";
                    callback.onToolResult(toolUseId, err, true);
                    pendingToolResults.add(createToolResult(toolUseId, err, true));
                    toolResultBlocks.add(new ToolResultBlock(toolUseId, err, true));
                } catch (Exception ex) {
                    pendingPermissions.remove(requestId);
                    String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    callback.onToolResult(toolUseId, "Error: " + errorMsg, true);
                    pendingToolResults.add(createToolResult(toolUseId, "Error: " + errorMsg, true));
                    toolResultBlocks.add(new ToolResultBlock(toolUseId, "Error: " + errorMsg, true));
                }
                continue;
            }

            // Allowed - execute tool
            try {
                ToolResult result = executeToolWithHook(tool, inputJson, toolContext, toolUseId, currentSession != null ? currentSession.getSessionId() : null);
                String resultJson = truncateToolOutput(result.getDataAsJson());
                callback.onToolResult(toolUseId, resultJson, result.isError());
                pendingToolResults.add(createToolResult(toolUseId, resultJson, result.isError()));
                toolResultBlocks.add(new ToolResultBlock(toolUseId, resultJson, result.isError()));
            } catch (Exception ex) {
                String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                callback.onToolResult(toolUseId, "Error: " + errorMsg, true);
                pendingToolResults.add(createToolResult(toolUseId, "Error: " + errorMsg, true));
                toolResultBlocks.add(new ToolResultBlock(toolUseId, "Error: " + errorMsg, true));
            }
        }

        // CRITICAL: Store tool results in state so they appear in conversation history
        // Without this, the model sees tool_use blocks with no corresponding tool_result,
        // causing it to re-execute the same tools on the next user message
        if (!toolResultBlocks.isEmpty()) {
            var toolResultMsg = UserMessage.withToolResults(toolResultBlocks);
            state.addMessage(toolResultMsg);
            // Persist full tool_result message
            if (currentSession != null) {
                String payload = serializeToolResultMessage(toolResultBlocks);
                sessionService.addFullMessage(
                        currentSession.getSessionId(), "user", "", null, payload, 0, 0);
            }
        }

        // Check if the model invoked a skill via the Skill tool
        Skill modelInvokedSkill = SkillTool.consumeLoadedSkill();

        // Build system prompt for next turn (may include the newly invoked skill)
        List<String> systemPrompt = request.getSystemPrompt();
        if (modelInvokedSkill != null) {
            log.info("Injecting model-invoked skill into next turn: /{}", modelInvokedSkill.getName());
            systemPrompt = new ArrayList<>(systemPrompt);
            systemPrompt.add(SystemPromptBuilder.getActiveSkillSection(List.of(modelInvokedSkill)));
        }

        // Check if context compaction is needed before next API call
        String sessionId = currentSession != null ? currentSession.getSessionId() : null;
        if (contextCompactor.shouldCompact(state)) {
            log.info("Auto-compacting context: last input tokens {} exceeded threshold",
                    state.getLastInputTokens());
            String summary = contextCompactor.compact(state, sessionId);
            if (summary != null) {
                callback.onTextDelta("\n[Context auto-compacted: " +
                        (state.getMessageCount() - appProperties.getCompactionKeepRecentMessages()) +
                        " older messages summarized]\n");
            }
        }

        List<Map<String, Object>> newMessages = buildApiMessages(state.getMessages());

        var nextRequest = new StreamRequest.Builder()
            .model(state.getMainLoopModel())
            .systemPrompt(systemPrompt)
            .messages(newMessages)
            .tools(request.getTools())
            .stream(true)
            .temperature(appProperties.getTemperature())
            .maxTokens(appProperties.getMaxTokens())
            .build();

        executeQueryLoop(nextRequest, callback, turn + 1);
    }

    /**
     * 截断过大的工具输出，防止上下文窗口被单个工具结果占满。
     */
    private String truncateToolOutput(String output) {
        int maxLen = appProperties.getMaxToolOutputLength();
        if (output == null || output.length() <= maxLen) return output;
        return output.substring(0, maxLen) +
                "\n\n[Output truncated: " + output.length() + " chars exceeded limit of " + maxLen + "]";
    }

    private String buildPermissionDescription(String toolName, String inputJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = MAPPER.readValue(inputJson, Map.class);
            return switch (toolName) {
                case "Write" -> "Create file: " + input.get("file_path");
                case "Edit" -> "Edit file: " + input.get("file_path");
                case "Bash" -> "Execute command: " + input.get("command");
                default -> "Use tool: " + toolName;
            };
        } catch (Exception e) {
            return "Use tool: " + toolName;
        }
    }

    // ---- Message building ----

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildApiMessages(List<Message> messages) {
        var result = new ArrayList<Map<String, Object>>();
        for (var msg : messages) {
            if (msg instanceof UserMessage um) {
                if (um.getToolResults().isEmpty()) {
                    result.add(createApiMessage("user", um.getContent()));
                } else {
                    var content = new ArrayList<Object>();
                    for (var trb : um.getToolResults()) {
                        content.add(createToolResultBlock(trb.getToolUseId(), trb.getContent(), trb.isError()));
                    }
                    result.add(createApiMessage("user", content));
                }
            } else if (msg instanceof AssistantMessage am) {
                var content = new ArrayList<Object>();
                if (am.getContent() != null && !am.getContent().isEmpty()) {
                    content.add(createTextBlock(am.getContent()));
                }
                for (var tub : am.getToolUseBlocks()) {
                    content.add(createToolUseBlock(tub.getId(), tub.getToolName(), tub.getInputJson()));
                }
                result.add(createApiMessage("assistant", content));
            }
        }
        return result;
    }

    private Map<String, Object> createApiMessage(String role, Object content) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private Map<String, Object> createTextBlock(String text) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private Map<String, Object> createToolUseBlock(String id, String name, String input) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "tool_use");
        block.put("id", id);
        block.put("name", name);
        try {
            block.put("input", MAPPER.readValue(input, Map.class));
        } catch (Exception e) {
            block.put("input", new HashMap<>());
        }
        return block;
    }

    private Map<String, Object> createToolResultBlock(String toolUseId, String content, boolean isError) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        if (isError) block.put("is_error", true);
        return block;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return (val instanceof Map) ? (Map<String, Object>) val : null;
    }

    private List<Map<String, Object>> buildApiTools() {
        var result = new ArrayList<Map<String, Object>>();
        for (var tool : toolRegistry.getEnabledTools()) {
            var toolDef = new LinkedHashMap<String, Object>();
            toolDef.put("name", tool.getName());
            toolDef.put("description", tool.getDescription());
            try {
                toolDef.put("input_schema", MAPPER.readValue(tool.getInputSchemaJson(), Map.class));
            } catch (Exception e) {
                toolDef.put("input_schema", new HashMap<String, Object>());
            }
            result.add(toolDef);
        }
        return result;
    }

    private Map<String, Object> createToolResult(String toolUseId, String content, boolean isError) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "user");
        var blocks = new ArrayList<Map<String, Object>>();
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        if (isError) block.put("is_error", true);
        blocks.add(block);
        msg.put("content", blocks);
        return msg;
    }

    // ---- Message serialization for persistence ----

    private ToolResult executeToolWithHook(Tool tool, String inputJson, ToolUseContext context,
                                           String toolUseId, String sessionId) {
        try {
            ToolResult result = tool.call(inputJson, context);
            // PostToolUse hook (informational, doesn't block)
            hookRunner.runHook("PostToolUse", tool.getName(), result.getDataAsJson(), workingDir, sessionId);
            return result;
        } catch (Exception ex) {
            // PostToolUseFailure hook
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            hookRunner.runHook("PostToolUseFailure", tool.getName(), "Error: " + errorMsg, workingDir, sessionId);
            throw new RuntimeException(ex);
        }
    }

    private String serializeAssistantMessage(AssistantMessage msg, String reasoning) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("messageType", "assistant");
            payload.put("content", msg.getContent());
            if (reasoning != null && !reasoning.isEmpty()) {
                payload.put("reasoning", reasoning);
            }
            if (msg.hasToolUse()) {
                var toolUses = new ArrayList<Map<String, Object>>();
                for (var tub : msg.getToolUseBlocks()) {
                    var tu = new LinkedHashMap<String, Object>();
                    tu.put("id", tub.getId());
                    tu.put("name", tub.getToolName());
                    tu.put("input", tub.getInputJson());
                    toolUses.add(tu);
                }
                payload.put("toolUseBlocks", toolUses);
            }
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize assistant message: {}", e.getMessage());
            return null;
        }
    }

    private String serializeToolResultMessage(List<ToolResultBlock> results) {
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("messageType", "user_tool_results");
            var resultList = new ArrayList<Map<String, Object>>();
            for (var trb : results) {
                var r = new LinkedHashMap<String, Object>();
                r.put("toolUseId", trb.getToolUseId());
                r.put("content", trb.getContent());
                r.put("isError", trb.isError());
                resultList.add(r);
            }
            payload.put("toolResultBlocks", resultList);
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize tool result message: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Message deserializeMessage(SessionMessageEntity sm) {
        try {
            if (sm.getMessagePayload() == null || sm.getMessagePayload().isEmpty()) {
                // Backward compat: old messages without payload
                return "user".equals(sm.getRole())
                        ? new UserMessage(sm.getContent())
                        : new AssistantMessage(sm.getContent());
            }

            var payload = MAPPER.readValue(sm.getMessagePayload(), Map.class);
            String messageType = (String) payload.get("messageType");

            if ("assistant".equals(messageType)) {
                var msg = new AssistantMessage(sm.getContent());
                var toolUses = (List<Map<String, Object>>) payload.get("toolUseBlocks");
                if (toolUses != null) {
                    for (var tu : toolUses) {
                        String inputJson = tu.get("input") instanceof String
                                ? (String) tu.get("input")
                                : MAPPER.writeValueAsString(tu.get("input"));
                        msg.addToolUseBlock(new ToolUseBlock(
                                (String) tu.get("id"), (String) tu.get("name"), inputJson));
                    }
                }
                return msg;
            } else if ("user_tool_results".equals(messageType)) {
                var results = (List<Map<String, Object>>) payload.get("toolResultBlocks");
                if (results != null && !results.isEmpty()) {
                    var blocks = new ArrayList<ToolResultBlock>();
                    for (var tr : results) {
                        blocks.add(new ToolResultBlock(
                                (String) tr.get("toolUseId"),
                                (String) tr.get("content"),
                                Boolean.TRUE.equals(tr.get("isError"))));
                    }
                    return UserMessage.withToolResults(blocks);
                }
                return new UserMessage(sm.getContent());
            } else {
                return "user".equals(sm.getRole())
                        ? new UserMessage(sm.getContent())
                        : new AssistantMessage(sm.getContent());
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize message payload, falling back to basic: {}", e.getMessage());
            return "user".equals(sm.getRole())
                    ? new UserMessage(sm.getContent())
                    : new AssistantMessage(sm.getContent());
        }
    }

    // ---- Session management ----

    public SessionEntity newSession() {
        currentSession = sessionService.createSession("New Session");
        appStore.getState().clearMessages();
        return currentSession;
    }

    public SessionEntity loadSession(String id) {
        var session = sessionService.loadSessionWithMessages(id);
        if (session == null) return null;
        currentSession = session;
        var state = appStore.getState();
        state.clearMessages();
        for (var sm : session.getMessages()) {
            Message msg = deserializeMessage(sm);
            state.addMessage(msg);
        }
        return session;
    }

    public String getCurrentSessionId() {
        return currentSession != null ? currentSession.getSessionId() : null;
    }

    public SessionEntity getCurrentSession() { return currentSession; }

    public List<Map<String, Object>> listSessions() {
        return sessionService.listSessionsSummary();
    }

    public boolean deleteSession(String id) {
        sessionService.deleteSession(id);
        if (currentSession != null && currentSession.getSessionId().equals(id)) {
            currentSession = null;
            appStore.getState().clearMessages();
        }
        return true;
    }

    public boolean renameSession(String id, String title) {
        var session = sessionService.loadSession(id);
        if (session == null) return false;
        sessionService.renameSession(id, title);
        if (currentSession != null && currentSession.getSessionId().equals(id)) {
            currentSession.setTitle(title);
        }
        return true;
    }

    public void abort() { aborted = true; }

    public Store<AppState> getAppStore() { return appStore; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public PermissionChecker getPermissionChecker() { return permissionChecker; }
}

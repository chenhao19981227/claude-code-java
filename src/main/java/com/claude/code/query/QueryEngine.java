package com.claude.code.query;

import com.claude.code.api.*;
import com.claude.code.config.AppProperties;
import com.claude.code.context.SystemPromptBuilder;
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

@Service
public class QueryEngine {
    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TURNS = 50;

    private final ApiClient client;
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private final Store<AppState> appStore;
    private final SessionService sessionService;
    private final AppProperties appProperties;
    private final SkillLoader skillLoader;
    private final McpManager mcpManager;
    private final SkillMatcher skillMatcher;
    private final String workingDir;

    private SessionEntity currentSession;
    private volatile boolean aborted;
    private List<Skill> allSkills;

    public interface QueryCallback {
        void onTextDelta(String text);
        void onReasoningDelta(String text);
        void onToolStart(String toolName, String toolUseId, String input);
        void onToolResult(String toolUseId, String result, boolean isError);
        void onError(String error);
        void onComplete();
    }

    public QueryEngine(ApiClient client, ToolRegistry toolRegistry,
                       SessionService sessionService, AppProperties appProperties,
                       SkillLoader skillLoader, McpManager mcpManager) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.sessionService = sessionService;
        this.appProperties = appProperties;
        this.skillLoader = skillLoader;
        this.mcpManager = mcpManager;
        this.workingDir = System.getProperty("user.dir");
        this.permissionChecker = new PermissionChecker(workingDir);
        this.appStore = new Store<>(new AppState(appProperties));
        this.appStore.getState().setCurrentWorkingDirectory(workingDir);
        this.skillMatcher = new SkillMatcher();
    }

    @PostConstruct
    public void init() {
        // Register MCP tool adapters
        for (var adapter : mcpManager.getToolAdapters()) {
            toolRegistry.register(adapter);
        }
        // Load skills
        allSkills = skillLoader.loadAllSkills();
    }

    public void submitMessage(String userInput, final QueryCallback callback) {
        if (userInput == null || userInput.trim().isEmpty()) return;
        aborted = false;

        // Auto-create session if none exists
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

        // Record in session via JPA
        sessionService.addMessage(currentSession.getSessionId(), "user", userInput);

        // Build system prompt with relevant skills
        List<Skill> relevantSkills = null;
        if (allSkills != null && !allSkills.isEmpty()) {
            relevantSkills = skillMatcher.findRelevantSkills(userInput, allSkills);
        }
        List<String> systemPrompt = SystemPromptBuilder.buildSystemPrompt(workingDir, appProperties, relevantSkills);

        // Build messages for API
        List<Map<String, Object>> apiMessages = buildApiMessages(state.getMessages());

        // Build tools
        List<Map<String, Object>> apiTools = buildApiTools();

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
                        sessionService.addMessage(currentSession.getSessionId(), "assistant", textBuffer.toString());
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

        for (var e : toolUseIds.entrySet()) {
            if (aborted) break;
            int index = e.getKey();
            String toolUseId = e.getValue();
            String toolName = toolNames.get(index);
            String inputJson = toolInputBuilders.containsKey(index) ? toolInputBuilders.get(index).toString() : "{}";

            Tool tool = toolRegistry.findByName(toolName);
            if (tool == null) {
                callback.onToolResult(toolUseId, "Unknown tool: " + toolName, true);
                pendingToolResults.add(createToolResult(toolUseId, "Unknown tool: " + toolName, true));
                continue;
            }

            PermissionResult permResult = tool.checkPermissions(inputJson, toolContext);
            if (permResult.isDeny()) {
                callback.onToolResult(toolUseId, "Denied: " + permResult.getMessage(), true);
                pendingToolResults.add(createToolResult(toolUseId, "Denied: " + permResult.getMessage(), true));
                continue;
            }

            if (permResult.isAsk() && tool.isReadOnly()) {
                permResult = PermissionResult.allow();
            }

            if (permResult.isAsk()) {
                callback.onToolResult(toolUseId, "Permission required: " + permResult.getMessage(), true);
                pendingToolResults.add(createToolResult(toolUseId, "Permission required: " + permResult.getMessage(), true));
                continue;
            }

            try {
                callback.onToolStart(toolName, toolUseId, inputJson);
                ToolResult result = tool.call(inputJson, toolContext);
                String resultJson = result.getDataAsJson();
                callback.onToolResult(toolUseId, resultJson, result.isError());
                pendingToolResults.add(createToolResult(toolUseId, resultJson, result.isError()));
            } catch (Exception ex) {
                String errorMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                callback.onToolResult(toolUseId, "Error: " + errorMsg, true);
                pendingToolResults.add(createToolResult(toolUseId, "Error: " + errorMsg, true));
            }
        }

        List<Map<String, Object>> newMessages = buildApiMessages(state.getMessages());
        newMessages.addAll(pendingToolResults);

        var nextRequest = new StreamRequest.Builder()
            .model(state.getMainLoopModel())
            .systemPrompt(request.getSystemPrompt())
            .messages(newMessages)
            .tools(request.getTools())
            .stream(true)
            .temperature(appProperties.getTemperature())
            .maxTokens(appProperties.getMaxTokens())
            .build();

        executeQueryLoop(nextRequest, callback, turn + 1);
    }

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

    // ---- Session management ----

    public SessionEntity newSession() {
        currentSession = sessionService.createSession("New Session");
        appStore.getState().clearMessages();
        return currentSession;
    }

    public SessionEntity loadSession(String id) {
        var session = sessionService.loadSession(id);
        if (session == null) return null;
        currentSession = session;

        var state = appStore.getState();
        state.clearMessages();
        for (var sm : session.getMessages()) {
            if ("user".equals(sm.getRole())) {
                state.addMessage(new UserMessage(sm.getContent()));
            } else if ("assistant".equals(sm.getRole())) {
                state.addMessage(new AssistantMessage(sm.getContent()));
            }
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

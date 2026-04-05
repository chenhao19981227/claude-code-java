package com.claude.code.query;

import com.claude.code.api.*;
import com.claude.code.context.SystemPromptBuilder;
import com.claude.code.mcp.McpManager;
import com.claude.code.message.*;
import com.claude.code.permission.PermissionChecker;
import com.claude.code.permission.PermissionResult;
import com.claude.code.session.Session;
import com.claude.code.session.SessionManager;
import com.claude.code.session.SessionMessage;
import com.claude.code.skill.Skill;
import com.claude.code.skill.SkillLoader;
import com.claude.code.skill.SkillMatcher;
import com.claude.code.state.Settings;
import com.claude.code.state.AppState;
import com.claude.code.state.Store;
import com.claude.code.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class QueryEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TURNS = 50;

    private final ApiClient client;
    private final ToolRegistry toolRegistry;
    private final PermissionChecker permissionChecker;
    private final Store<AppState> appStore;
    private final String workingDir;
    private final Settings settings;
    private SessionManager sessionManager;
    private Session currentSession;
    private volatile boolean aborted;
    private SkillLoader skillLoader;
    private SkillMatcher skillMatcher;
    private List<Skill> allSkills;
    private McpManager mcpManager;

    public interface QueryCallback {
        void onTextDelta(String text);
        void onReasoningDelta(String text);
        void onToolStart(String toolName, String toolUseId, String input);
        void onToolResult(String toolUseId, String result, boolean isError);
        void onError(String error);
        void onComplete();
    }

    public QueryEngine(ApiClient client, String workingDir) {
        this(client, workingDir, null);
    }

    public QueryEngine(ApiClient client, String workingDir, Settings settings) {
        this.client = client;
        this.settings = settings;
        this.toolRegistry = new ToolRegistry();
        this.permissionChecker = new PermissionChecker(workingDir);
        this.workingDir = workingDir != null ? workingDir : System.getProperty("user.dir");
        this.appStore = new Store<>(new AppState());
        this.appStore.getState().setCurrentWorkingDirectory(this.workingDir);
        this.sessionManager = new SessionManager(this.workingDir);
        this.currentSession = null;
        if (settings != null) {
            this.appStore.getState().setMainLoopModel(settings.getEffectiveModel());
        }
        registerCoreTools();
        initSkills();
        initMcpServers();
    }

    private void registerCoreTools() {
        toolRegistry.register(new BashTool());
        toolRegistry.register(new FileReadTool());
        toolRegistry.register(new FileEditTool());
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());
        toolRegistry.register(new TodoWriteTool());
    }

    private void initSkills() {
        if (settings != null && settings.getSkillDirectories() != null && !settings.getSkillDirectories().isEmpty()) {
            skillLoader = new SkillLoader(settings.getSkillDirectories());
            skillMatcher = new SkillMatcher();
            allSkills = skillLoader.loadAllSkills();
            System.out.println("Loaded " + allSkills.size() + " skills from " + settings.getSkillDirectories().size() + " directories");
        } else {
            // Use default skill directory
            skillLoader = new SkillLoader(new ArrayList<String>());
            skillMatcher = new SkillMatcher();
            allSkills = skillLoader.loadAllSkills();
            if (!allSkills.isEmpty()) {
                System.out.println("Loaded " + allSkills.size() + " skills from default directories");
            }
        }
    }

    private void initMcpServers() {
        if (settings != null && settings.getMcpServers() != null && !settings.getMcpServers().isEmpty()) {
            mcpManager = new McpManager();
            mcpManager.init(settings.getMcpServerConfigs());
            for (Tool adapter : mcpManager.getToolAdapters()) {
                toolRegistry.register(adapter);
            }
        }
    }

    public void submitMessage(String userInput, final QueryCallback callback) {
        if (userInput == null || userInput.trim().isEmpty()) return;
        aborted = false;

        // Auto-create session if none exists
        if (currentSession == null) {
            String title = userInput.length() > 50 ? userInput.substring(0, 50) : userInput;
            currentSession = sessionManager.createSession(title);
        } else if (currentSession.getTitle().equals("New Session") || currentSession.getMessages().isEmpty()) {
            // Auto-title from first user message
            String title = userInput.length() > 50 ? userInput.substring(0, 50) : userInput;
            currentSession.setTitle(title);
        }

        AppState state = appStore.getState();
        UserMessage userMsg = new UserMessage(userInput);
        state.addMessage(userMsg);

        // Record in session
        currentSession.addMessage(new SessionMessage("user", userInput));

        // Build system prompt with relevant skills
        List<Skill> relevantSkills = null;
        if (skillMatcher != null && allSkills != null && !allSkills.isEmpty()) {
            relevantSkills = skillMatcher.findRelevantSkills(userInput, allSkills);
        }
        List<String> systemPrompt = SystemPromptBuilder.buildSystemPrompt(workingDir, settings, relevantSkills);

        // Build messages for API
        List<Map<String, Object>> apiMessages = buildApiMessages(state.getMessages());

        // Build tools
        List<Map<String, Object>> apiTools = buildApiTools();

        // Create stream request
        StreamRequest request = new StreamRequest.Builder()
            .model(state.getMainLoopModel())
            .systemPrompt(systemPrompt)
            .messages(apiMessages)
            .tools(apiTools)
            .stream(true)
            .build();

        // Stream and handle tool calls in a loop
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
                    StringBuilder inputBuilder = toolInputBuilders.get(index);
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

            @Override public void onMessageDelta(Map<String, Object> data) {
                Map<String, Object> delta = getMap(data, "delta");
                if (delta != null && delta.containsKey("stop_reason")) {
                    String stopReason = String.valueOf(delta.get("stop_reason"));
                    if ("end_turn".equals(stopReason)) {
                        // Normal completion
                    }
                }
            }

            @Override public void onMessageStop(Map<String, Object> data) {}

            @Override public void onReasoningDelta(String text) {
                callback.onReasoningDelta(text);
            }

            @Override public void onError(Throwable error) {
                callback.onError(error.getMessage());
            }

            @Override public void onComplete() {
                AppState state = appStore.getState();

                // Save assistant text
                if (textBuffer.length() > 0) {
                    AssistantMessage assistantMsg = new AssistantMessage(textBuffer.toString());
                    for (Map.Entry<Integer, String> e : toolUseIds.entrySet()) {
                        assistantMsg.addToolUseBlock(new ToolUseBlock(e.getValue(), toolNames.get(e.getValue()), toolInputBuilders.getOrDefault(e.getKey(), new StringBuilder()).toString()));
                    }
                    state.addMessage(assistantMsg);

                    // Record assistant message in session
                    if (currentSession != null) {
                        currentSession.addMessage(new SessionMessage("assistant", textBuffer.toString()));
                    }
                }

                // If there were tool calls, execute them and continue
                if (!toolUseIds.isEmpty() && !aborted) {
                    executeToolsAndContinue(toolUseIds, toolNames, toolInputBuilders, callback, turn, request, pendingToolResults);
                } else {
                    // Auto-save session on completion
                    saveCurrentSession();
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
        ToolUseContext toolContext = new ToolUseContext();
        toolContext.setWorkingDirectory(workingDir);
        toolContext.setPermissionChecker(permissionChecker);
        toolContext.setAppStore(appStore);
        toolContext.setAllTools(toolRegistry.getEnabledTools(state));

        // Execute each tool
        for (Map.Entry<Integer, String> e : toolUseIds.entrySet()) {
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

            // Check permissions
            PermissionResult permResult = tool.checkPermissions(inputJson, toolContext);
            if (permResult.isDeny()) {
                callback.onToolResult(toolUseId, "Denied: " + permResult.getMessage(), true);
                pendingToolResults.add(createToolResult(toolUseId, "Denied: " + permResult.getMessage(), true));
                continue;
            }

            // Auto-allow for read-only tools
            if (permResult.isAsk() && tool.isReadOnly()) {
                permResult = PermissionResult.allow();
            }

            if (permResult.isAsk()) {
                callback.onToolResult(toolUseId, "Permission required: " + permResult.getMessage(), true);
                pendingToolResults.add(createToolResult(toolUseId, "Permission required: " + permResult.getMessage(), true));
                continue;
            }

            // Execute tool
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

        // Build next request with tool results
        List<Map<String, Object>> newMessages = buildApiMessages(state.getMessages());
        newMessages.addAll(pendingToolResults);

        StreamRequest nextRequest = new StreamRequest.Builder()
            .model(state.getMainLoopModel())
            .systemPrompt(request.getSystemPrompt())
            .messages(newMessages)
            .tools(request.getTools())
            .stream(true)
            .build();

        // Continue loop
        executeQueryLoop(nextRequest, callback, turn + 1);
    }

    private List<Map<String, Object>> buildApiMessages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof UserMessage) {
                UserMessage um = (UserMessage) msg;
                if (um.getToolResults().isEmpty()) {
                    result.add(createApiMessage("user", um.getContent()));
                } else {
                    List<Object> content = new ArrayList<>();
                    for (ToolResultBlock trb : um.getToolResults()) {
                        content.add(createToolResultBlock(trb.getToolUseId(), trb.getContent(), trb.isError()));
                    }
                    result.add(createApiMessage("user", content));
                }
            } else if (msg instanceof AssistantMessage) {
                AssistantMessage am = (AssistantMessage) msg;
                List<Object> content = new ArrayList<>();
                if (am.getContent() != null && !am.getContent().isEmpty()) {
                    content.add(createTextBlock(am.getContent()));
                }
                for (ToolUseBlock tub : am.getToolUseBlocks()) {
                    content.add(createToolUseBlock(tub.getId(), tub.getToolName(), tub.getInputJson()));
                }
                result.add(createApiMessage("assistant", content));
            }
        }
        return result;
    }

    private Map<String, Object> createApiMessage(String role, Object content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private Map<String, Object> createTextBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private Map<String, Object> createToolUseBlock(String id, String name, String input) {
        Map<String, Object> block = new LinkedHashMap<>();
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
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        if (isError) block.put("is_error", true);
        return block;
    }

    private Map<String, Object> getMap(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return (val instanceof Map) ? (Map<String, Object>) val : null;
    }

    private List<Map<String, Object>> buildApiTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Tool> tools = toolRegistry.getEnabledTools();
        for (Tool tool : tools) {
            Map<String, Object> toolDef = new LinkedHashMap<>();
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
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "user");
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", content);
        if (isError) block.put("is_error", true);
        blocks.add(block);
        msg.put("content", blocks);
        return msg;
    }

    /**
     * Create a new session, clearing current messages.
     */
    public Session newSession() {
        currentSession = sessionManager.createSession("New Session");
        appStore.getState().clearMessages();
        return currentSession;
    }

    /**
     * Load an existing session by ID, populating messages.
     */
    public Session loadSession(String id) {
        Session session = sessionManager.loadSession(id);
        if (session == null) return null;
        currentSession = session;

        // Populate app messages from session
        AppState state = appStore.getState();
        state.clearMessages();
        for (SessionMessage sm : session.getMessages()) {
            if ("user".equals(sm.getRole())) {
                state.addMessage(new UserMessage(sm.getContent()));
            } else if ("assistant".equals(sm.getRole())) {
                state.addMessage(new AssistantMessage(sm.getContent()));
            }
        }
        return session;
    }

    /**
     * Get current session ID, or null if no session is active.
     */
    public String getCurrentSessionId() {
        return currentSession != null ? currentSession.getId() : null;
    }

    /**
     * Get current session, or null.
     */
    public Session getCurrentSession() {
        return currentSession;
    }

    /**
     * List all sessions as summary (metadata only).
     */
    public List<Map<String, Object>> listSessions() {
        List<Session> sessions = sessionManager.listSessionsSummary();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Session s : sessions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("createdAt", s.getCreatedAt());
            m.put("updatedAt", s.getUpdatedAt());
            result.add(m);
        }
        return result;
    }

    /**
     * Delete a session by ID.
     */
    public boolean deleteSession(String id) {
        sessionManager.deleteSession(id);
        if (currentSession != null && currentSession.getId().equals(id)) {
            currentSession = null;
            appStore.getState().clearMessages();
        }
        return true;
    }

    /**
     * Rename a session.
     */
    public boolean renameSession(String id, String title) {
        Session session = sessionManager.loadSession(id);
        if (session == null) return false;
        session.setTitle(title);
        sessionManager.saveSession(session);
        if (currentSession != null && currentSession.getId().equals(id)) {
            currentSession.setTitle(title);
        }
        return true;
    }

    private void saveCurrentSession() {
        if (currentSession != null) {
            try {
                sessionManager.saveSession(currentSession);
            } catch (Exception e) {
                System.err.println("[QueryEngine] Failed to save session: " + e.getMessage());
            }
        }
    }

    public void abort() {
        aborted = true;
        if (mcpManager != null) {
            mcpManager.shutdown();
        }
    }
    public Store<AppState> getAppStore() { return appStore; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public PermissionChecker getPermissionChecker() { return permissionChecker; }
}

package com.claude.code.state;

import com.claude.code.config.AppProperties;
import com.claude.code.message.Message;
import com.claude.code.permission.PermissionMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppState {
    private String mainLoopModel;
    private PermissionMode permissionMode = PermissionMode.DEFAULT;
    private boolean verbose;
    private boolean thinkingEnabled;
    private List<Message> messages = new ArrayList<>();
    private String currentWorkingDirectory = System.getProperty("user.dir");
    private Map<String, String> settings = new HashMap<>();
    private boolean isRunning;
    private String sessionStartTime = String.valueOf(System.currentTimeMillis());
    private int totalInputTokens;
    private int totalOutputTokens;
    private int lastInputTokens; // from most recent API call, used for compaction check
    private boolean compacted; // flag to prevent re-compaction in same turn

    public AppState(AppProperties props) {
        this.mainLoopModel = props.getEffectiveModel();
        this.currentWorkingDirectory = System.getProperty("user.dir");
    }

    public String getMainLoopModel() { return mainLoopModel; }
    public void setMainLoopModel(String mainLoopModel) { this.mainLoopModel = mainLoopModel; }

    public PermissionMode getPermissionMode() { return permissionMode; }
    public void setPermissionMode(PermissionMode permissionMode) { this.permissionMode = permissionMode; }

    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean verbose) { this.verbose = verbose; }

    public boolean isThinkingEnabled() { return thinkingEnabled; }
    public void setThinkingEnabled(boolean thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }

    public List<Message> getMessages() { return messages; }
    public void addMessage(Message message) { this.messages.add(message); }
    public void clearMessages() { this.messages.clear(); }
    public int getMessageCount() { return messages.size(); }

    public String getCurrentWorkingDirectory() { return currentWorkingDirectory; }
    public void setCurrentWorkingDirectory(String cwd) { this.currentWorkingDirectory = cwd; }

    public Map<String, String> getSettings() { return settings; }
    public String getSetting(String key) { return settings.get(key); }
    public void setSetting(String key, String value) { settings.put(key, value); }

    public boolean isRunning() { return isRunning; }
    public void setRunning(boolean running) { isRunning = running; }

    public String getSessionStartTime() { return sessionStartTime; }
    public int getTotalInputTokens() { return totalInputTokens; }
    public void addInputTokens(int tokens) { this.totalInputTokens += tokens; }
    public int getTotalOutputTokens() { return totalOutputTokens; }
    public void addOutputTokens(int tokens) { this.totalOutputTokens += tokens; }
    public int getLastInputTokens() { return lastInputTokens; }
    public void setLastInputTokens(int tokens) { this.lastInputTokens = tokens; }
    public boolean isCompacted() { return compacted; }
    public void setCompacted(boolean compacted) { this.compacted = compacted; }
}

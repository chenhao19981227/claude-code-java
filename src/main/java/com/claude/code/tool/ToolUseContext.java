package com.claude.code.tool;

import com.claude.code.permission.PermissionChecker;
import com.claude.code.state.AppState;
import com.claude.code.state.Store;

import java.util.List;

public class ToolUseContext {
    private String workingDirectory;
    private PermissionChecker permissionChecker;
    private Store<AppState> appStore;
    private List<Tool> allTools;
    private volatile boolean aborted;

    public ToolUseContext() {}

    public boolean isAborted() { return aborted; }
    public void abort() { this.aborted = true; }

    public AppState getAppState() { return appStore != null ? appStore.getState() : null; }
    public String getWorkingDirectory() { return workingDirectory; }
    public PermissionChecker getPermissionChecker() { return permissionChecker; }
    public List<Tool> getAllTools() { return allTools; }

    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }
    public void setPermissionChecker(PermissionChecker permissionChecker) { this.permissionChecker = permissionChecker; }
    public void setAppStore(Store<AppState> appStore) { this.appStore = appStore; }
    public void setAllTools(List<Tool> allTools) { this.allTools = allTools; }
}

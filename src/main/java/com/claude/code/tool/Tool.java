package com.claude.code.tool;

import com.claude.code.permission.PermissionResult;
import com.claude.code.permission.PermissionChecker;

public abstract class Tool {
    private final String name;
    private final String description;
    private final String searchHint;
    private final int maxResultSizeChars;
    private final boolean readOnly;
    private final boolean concurrencySafe;
    private final boolean enabled;

    protected Tool(String name, String description) {
        this(name, description, "", 50000, false, false, true);
    }

    protected Tool(String name, String description, String searchHint, int maxResultSizeChars,
                   boolean readOnly, boolean concurrencySafe) {
        this(name, description, searchHint, maxResultSizeChars, readOnly, concurrencySafe, true);
    }

    protected Tool(String name, String description, String searchHint, int maxResultSizeChars,
                   boolean readOnly, boolean concurrencySafe, boolean enabled) {
        this.name = name;
        this.description = description;
        this.searchHint = searchHint;
        this.maxResultSizeChars = maxResultSizeChars;
        this.readOnly = readOnly;
        this.concurrencySafe = concurrencySafe;
        this.enabled = enabled;
    }

    public abstract ToolResult call(String inputJson, ToolUseContext context) throws Exception;

    public PermissionResult checkPermissions(String inputJson, ToolUseContext context) {
        return PermissionResult.allow();
    }

    public boolean isEnabled() { return enabled; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getSearchHint() { return searchHint; }
    public int getMaxResultSizeChars() { return maxResultSizeChars; }
    public boolean isReadOnly() { return readOnly; }
    public boolean isConcurrencySafe() { return concurrencySafe; }

    public String getInputSchemaJson() { return "{}"; }
    public String getUserFacingName() { return name; }
}

package com.claude.code.tool;

import com.claude.code.message.Message;
import java.util.List;

public class ToolResult {
    private final Object data;
    private final List<Message> newMessages;
    private final boolean isError;

    private ToolResult(Object data, List<Message> newMessages, boolean isError) {
        this.data = data;
        this.newMessages = newMessages;
        this.isError = isError;
    }

    public Object getData() { return data; }
    public List<Message> getNewMessages() { return newMessages; }
    public boolean isError() { return isError; }

    public String getDataAsJson() {
        if (data == null) return "null";
        if (data instanceof String) return (String) data;
        return data.toString();
    }

    public static ToolResult success(Object data) {
        return new ToolResult(data, null, false);
    }

    public static ToolResult success(Object data, List<Message> messages) {
        return new ToolResult(data, messages, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, null, true);
    }
}

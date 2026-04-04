package com.claude.code.message;

import java.util.ArrayList;
import java.util.List;

public class UserMessage extends Message {
    private String content;
    private boolean isMeta;
    private boolean isVirtual;
    private List<ToolResultBlock> toolResults;

    public UserMessage() { super(Type.USER); this.content = ""; }

    public UserMessage(String content) { super(Type.USER); this.content = content; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isMeta() { return isMeta; }
    public void setMeta(boolean meta) { isMeta = meta; }
    public boolean isVirtual() { return isVirtual; }
    public void setVirtual(boolean virtual) { isVirtual = virtual; }
    public List<ToolResultBlock> getToolResults() {
        if (toolResults == null) toolResults = new ArrayList<>();
        return toolResults;
    }
    public void addToolResult(ToolResultBlock result) { getToolResults().add(result); }

    public static UserMessage withToolResults(List<ToolResultBlock> results) {
        UserMessage msg = new UserMessage();
        msg.toolResults = results;
        return msg;
    }
}

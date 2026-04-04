package com.claude.code.message;

import java.util.ArrayList;
import java.util.List;

public class AssistantMessage extends Message {
    private String content;
    private String model;
    private boolean isApiErrorMessage;
    private List<ToolUseBlock> toolUseBlocks;
    private int inputTokens;
    private int outputTokens;

    public AssistantMessage() { super(Type.ASSISTANT); this.content = ""; }

    public AssistantMessage(String content) { super(Type.ASSISTANT); this.content = content; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isApiErrorMessage() { return isApiErrorMessage; }
    public void setApiErrorMessage(boolean apiErrorMessage) { isApiErrorMessage = apiErrorMessage; }
    public List<ToolUseBlock> getToolUseBlocks() {
        if (toolUseBlocks == null) toolUseBlocks = new ArrayList<>();
        return toolUseBlocks;
    }
    public void addToolUseBlock(ToolUseBlock block) { getToolUseBlocks().add(block); }
    public boolean hasToolUse() { return toolUseBlocks != null && !toolUseBlocks.isEmpty(); }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
}

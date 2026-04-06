package com.claude.code.compaction;

import com.claude.code.api.ApiClient;
import com.claude.code.api.StreamRequest;
import com.claude.code.api.StreamListener;
import com.claude.code.api.TokenUsage;
import com.claude.code.config.AppProperties;
import com.claude.code.message.AssistantMessage;
import com.claude.code.message.Message;
import com.claude.code.message.UserMessage;
import com.claude.code.service.SessionService;
import com.claude.code.state.AppState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 上下文窗口压缩服务（Claude Code 兼容）。
 *
 * <p>当对话输入 token 数超过上下文窗口的压缩阈值时，自动触发压缩：
 * <ol>
 *   <li>将旧消息序列化为文本，发送给模型生成摘要</li>
 *   <li>用一条摘要消息替换所有旧消息</li>
 *   <li>保留最近 N 条消息不变</li>
 * </ol>
 *
 * <p>用户也可以通过 /compact 命令手动触发压缩。
 */
@Service
public class ContextCompactor {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    private final ApiClient client;
    private final AppProperties appProperties;
    private final SessionService sessionService;

    public ContextCompactor(ApiClient client, AppProperties appProperties, SessionService sessionService) {
        this.client = client;
        this.appProperties = appProperties;
        this.sessionService = sessionService;
    }

    /**
     * 检查是否需要压缩。
     * 当最近一次 API 调用的 inputTokens 超过 contextWindowSize * compactionThreshold 时返回 true。
     */
    public boolean shouldCompact(AppState state) {
        if (state.isCompacted()) return false;
        int lastInput = state.getLastInputTokens();
        int threshold = (int) (appProperties.getContextWindowSize() * appProperties.getCompactionThreshold());
        if (lastInput <= 0) return false;
        if (lastInput < threshold) return false;
        // 至少需要 keepRecent + 2 条消息才有压缩意义
        if (state.getMessageCount() <= appProperties.getCompactionKeepRecentMessages() + 2) return false;
        return true;
    }

    /**
     * 执行上下文压缩。
     *
     * @return 压缩后的摘要文本，如果压缩失败返回 null
     */
    public String compact(AppState state, String sessionId) {
        List<Message> messages = state.getMessages();
        int keepRecent = appProperties.getCompactionKeepRecentMessages();

        if (messages.size() <= keepRecent + 2) {
            log.info("Not enough messages to compact ({} messages, need >{})", messages.size(), keepRecent + 2);
            return null;
        }

        // Split: older messages to summarize, recent messages to keep
        int splitIndex = messages.size() - keepRecent;
        List<Message> olderMessages = messages.subList(0, splitIndex);
        List<Message> recentMessages = messages.subList(splitIndex, messages.size());

        // Serialize older messages into a conversation text for summarization
        String conversationText = serializeMessagesForSummary(olderMessages);
        if (conversationText.isEmpty()) {
            log.info("No meaningful content in older messages to summarize");
            return null;
        }

        log.info("Compacting context: {} older messages being summarized, {} recent messages preserved",
                olderMessages.size(), recentMessages.size());

        // Generate summary via API
        String summary = generateSummary(conversationText);
        if (summary == null || summary.isEmpty()) {
            log.warn("Failed to generate summary, skipping compaction");
            return null;
        }

        // Replace older messages with summary in state
        state.clearMessages();
        var summaryMsg = new UserMessage(
                "[Context Compacted]\n\nThe following is a summary of the earlier conversation:\n\n" + summary);
        state.addMessage(summaryMsg);
        for (var msg : recentMessages) {
            state.addMessage(msg);
        }
        state.setCompacted(true);

        // Persist summary to session
        if (sessionId != null) {
            String payload = "{\"messageType\":\"compaction_summary\",\"summary\":" +
                    escapeJson(summary) + "}";
            sessionService.addFullMessage(sessionId, "user",
                    "[Context Compacted]", null, payload, 0, 0);
        }

        log.info("Context compaction complete. Summary length: {} chars", summary.length());
        return summary;
    }

    /**
     * 强制压缩（/compact 命令用），忽略阈值检查。
     */
    public String forceCompact(AppState state, String sessionId) {
        List<Message> messages = state.getMessages();
        int keepRecent = appProperties.getCompactionKeepRecentMessages();

        if (messages.size() <= keepRecent + 2) {
            return "Not enough messages to compact.";
        }

        int splitIndex = messages.size() - keepRecent;
        List<Message> olderMessages = messages.subList(0, splitIndex);
        List<Message> recentMessages = messages.subList(splitIndex, messages.size());

        String conversationText = serializeMessagesForSummary(olderMessages);
        if (conversationText.isEmpty()) {
            return "No meaningful content in older messages to summarize.";
        }

        log.info("Force compacting context: {} older messages, {} recent messages preserved",
                olderMessages.size(), recentMessages.size());

        String summary = generateSummary(conversationText);
        if (summary == null || summary.isEmpty()) {
            return "Failed to generate summary.";
        }

        state.clearMessages();
        var summaryMsg = new UserMessage(
                "[Context Compacted]\n\nThe following is a summary of the earlier conversation:\n\n" + summary);
        state.addMessage(summaryMsg);
        for (var msg : recentMessages) {
            state.addMessage(msg);
        }
        state.setCompacted(true);

        if (sessionId != null) {
            String payload = "{\"messageType\":\"compaction_summary\",\"summary\":" +
                    escapeJson(summary) + "}";
            sessionService.addFullMessage(sessionId, "user",
                    "[Context Compacted]", null, payload, 0, 0);
        }

        return "Context compacted. " + olderMessages.size() + " messages summarized, " +
                recentMessages.size() + " recent messages preserved.";
    }

    /**
     * 将消息列表序列化为可读的对话文本，用于摘要生成。
     */
    private String serializeMessagesForSummary(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            if (msg instanceof UserMessage um) {
                if (!um.getToolResults().isEmpty()) {
                    // Tool results: summarize briefly
                    sb.append("[User - Tool Results]\n");
                    for (var tr : um.getToolResults()) {
                        String content = tr.getContent();
                        if (content != null && content.length() > 500) {
                            content = content.substring(0, 500) + "... (truncated)";
                        }
                        sb.append("  Tool result: ").append(content).append("\n");
                    }
                } else if (um.getContent() != null && !um.getContent().isEmpty()) {
                    sb.append("[User]: ").append(um.getContent()).append("\n\n");
                }
            } else if (msg instanceof AssistantMessage am) {
                if (am.getContent() != null && !am.getContent().isEmpty()) {
                    sb.append("[Assistant]: ").append(am.getContent()).append("\n\n");
                }
                if (am.hasToolUse()) {
                    for (var tu : am.getToolUseBlocks()) {
                        sb.append("  [Tool Call: ").append(tu.getToolName()).append("]\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 调用 API 生成对话摘要。
     * 使用 Claude Code 风格的摘要 prompt。
     */
    private String generateSummary(String conversationText) {
        var systemPrompt = List.of(
                "You are a helpful AI assistant tasked with summarizing conversations.",
                "Your task is to create a detailed summary of the conversation so far.",
                "Wrap your analysis in <analysis> tags and your summary in <summary> tags.",
                "The summary should preserve:",
                "1. Primary request and intent",
                "2. Key decisions made",
                "3. Files that were read, created, or modified (with paths)",
                "4. Important code changes or implementations",
                "5. Errors encountered and how they were resolved",
                "6. Current state of the work",
                "7. Any pending tasks or next steps",
                "Be concise but comprehensive. Focus on information that would be needed to continue the work."
        );

        var userContent = "Please summarize the following conversation:\n\n" + conversationText;

        var messages = List.<Map<String, Object>>of(Map.of("role", "user", "content", (Object) userContent));

        var request = new StreamRequest.Builder()
                .model(appProperties.getEffectiveModel())
                .systemPrompt(systemPrompt)
                .messages(messages)
                .stream(false)
                .temperature(0.3)
                .maxTokens(4096)
                .build();

        var summaryBuffer = new StringBuilder();

        try {
            client.streamMessage(request, new StreamListener() {
                @Override public void onMessageStart(Map<String, Object> data) {}
                @Override public void onContentBlockStart(Map<String, Object> data) {}
                @Override public void onContentBlockDelta(Map<String, Object> data) {
                    Map<String, Object> delta = (Map<String, Object>) data.get("delta");
                    if (delta != null && "text_delta".equals(delta.get("type"))) {
                        summaryBuffer.append(delta.get("text"));
                    }
                }
                @Override public void onContentBlockStop(Map<String, Object> data) {}
                @Override public void onMessageDelta(Map<String, Object> data) {}
                @Override public void onMessageStop(Map<String, Object> data) {}
                @Override public void onReasoningDelta(String text) {}
                @Override public void onTokenUsage(TokenUsage usage) {}
                @Override public void onError(Throwable error) {
                    log.error("Error generating summary: {}", error.getMessage());
                }
                @Override public void onComplete() {}
            });
        } catch (Exception e) {
            log.error("Failed to call API for summary generation: {}", e.getMessage());
            return null;
        }

        String result = summaryBuffer.toString().trim();
        // Extract just the <summary> content if present
        if (result.contains("<summary>")) {
            int start = result.indexOf("<summary>") + "<summary>".length();
            int end = result.indexOf("</summary>");
            if (end > start) {
                result = result.substring(start, end).trim();
            }
        }

        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}

package com.claude.code.cli;

import com.claude.code.command.*;
import com.claude.code.query.QueryEngine;
import com.claude.code.state.AppState;

import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

import java.util.*;

public class Repl implements QueryEngine.QueryCallback {
    private final QueryEngine engine;
    private final LineReader lineReader;
    private final CommandRegistry commandRegistry;
    private final List<String> messageHistory = new ArrayList<>();
    private boolean running = true;

    private static final String PROMPT = "claude> ";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GRAY = "\u001B[90m";

    public Repl(QueryEngine engine) {
        this.engine = engine;
        this.commandRegistry = new CommandRegistry();
        this.lineReader = LineReaderBuilder.builder()
            .parser(new DefaultParser())
            .completer(new ReplCompleter())
            .history(new DefaultHistory())
            .variable(LineReader.HISTORY_FILE, System.getProperty("user.home") + "/.claude/history")
            .build();
        registerCommands();
    }

    private void registerCommands() {
        commandRegistry.register(new HelpCommand());
        commandRegistry.register(new ClearCommand());
        commandRegistry.register(new ModelCommand());
        commandRegistry.register(new CompactCommand());
        commandRegistry.register(new CommitCommand());
    }

    public void start() {
        println(ANSI_BOLD + ANSI_CYAN + "Claude Code Java v1.0.0" + ANSI_RESET);
        println("Type a message to chat with Claude, or /help for commands.");
        println(ANSI_GRAY + "Working directory: " + engine.getAppStore().getState().getCurrentWorkingDirectory() + ANSI_RESET);
        System.out.println();

        while (running) {
            try {
                String line = lineReader.readLine(PROMPT);
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;

                // Check for slash commands
                if (line.startsWith("/")) {
                    handleCommand(line);
                } else {
                    // Submit to query engine
                    messageHistory.add(line);
                    final String userInput = line;
                    engine.submitMessage(userInput, this);
                }
            } catch (UserInterruptException e) {
                println("\n" + ANSI_YELLOW + "Use Ctrl+C again or /exit to quit" + ANSI_RESET);
            } catch (EndOfFileException e) {
                break;
            } catch (Exception e) {
                println(ANSI_RED + "Error: " + e.getMessage() + ANSI_RESET);
            }
        }
        println(ANSI_GRAY + "\nGoodbye!" + ANSI_RESET);
    }

    private void handleCommand(String line) {
        String cmdLine = line.substring(1);
        String[] parts = cmdLine.split("\\s+", 2);
        String cmdName = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

        Command cmd = commandRegistry.findByName(cmdName);
        if (cmd == null) {
            println(ANSI_RED + "Unknown command: /" + cmdName + ". Type /help for available commands." + ANSI_RESET);
            return;
        }

        switch (cmd.getType()) {
            case LOCAL:
                LocalCommandResult result = cmd.executeLocal(args, null);
                if (result.getType() == LocalCommandResult.ResultType.TEXT && result.getValue() != null) {
                    println(result.getValue());
                } else if (result.getType() == LocalCommandResult.ResultType.COMPACT) {
                    handleCompact();
                }
                break;
            case PROMPT:
                String prompt = cmd.getPromptForCommand(args, null);
                messageHistory.add(prompt);
                engine.submitMessage(prompt, this);
                break;
            default:
                println(ANSI_RED + "Command type not supported: " + cmd.getType() + ANSI_RESET);
        }
    }

    private void handleCompact() {
        AppState state = engine.getAppStore().getState();
        List<com.claude.code.message.Message> messages = state.getMessages();
        if (messages.size() <= 4) {
            println(ANSI_YELLOW + "Conversation is already compact." + ANSI_RESET);
            return;
        }
        // Keep first user message and last few messages
        List<com.claude.code.message.Message> compacted = new ArrayList<>();
        compacted.add(messages.get(0)); // first user message
        int keep = Math.max(4, messages.size() - 4);
        for (int i = keep; i < messages.size(); i++) {
            compacted.add(messages.get(i));
        }
        state.clearMessages();
        for (com.claude.code.message.Message msg : compacted) {
            state.addMessage(msg);
        }
        println(ANSI_GREEN + "Conversation compacted (" + messages.size() + " -> " + compacted.size() + " messages)" + ANSI_RESET);
    }

    // QueryCallback implementation
    @Override
    public synchronized void onTextDelta(String text) {
        System.out.print(text);
        System.out.flush();
    }

    @Override
    public void onReasoningDelta(String text) {
        // Not displayed in terminal REPL
    }

    @Override
    public void onToolStart(String toolName, String toolUseId, String input) {
        System.out.println();
        println(ANSI_YELLOW + "⏺ " + toolName + ANSI_RESET);
        // Show truncated input
        String truncated = input.length() > 80 ? input.substring(0, 80) + "..." : input;
        println(ANSI_GRAY + "  Input: " + truncated + ANSI_RESET);
    }

    @Override
    public void onToolResult(String toolUseId, String result, boolean isError) {
        if (isError) {
            String truncated = result.length() > 200 ? result.substring(0, 200) + "..." : result;
            println(ANSI_RED + "  Error: " + truncated + ANSI_RESET);
        } else {
            String truncated = result.length() > 200 ? result.substring(0, 200) + "..." : result;
            println(ANSI_GREEN + "  Result: " + truncated + ANSI_RESET);
        }
    }

    @Override
    public void onError(String error) {
        println(ANSI_RED + "Error: " + error + ANSI_RESET);
    }

    @Override
    public void onComplete() {
        System.out.println();
        System.out.flush();
    }

    public void stop() { running = false; }

    private void println(String text) { System.out.println(text); }

    private class ReplCompleter implements Completer {
        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            String word = line.word().toLowerCase();
            // Slash commands
            if (word.startsWith("/")) {
                for (String name : commandRegistry.getCommandNames()) {
                    if (name.toLowerCase().startsWith(word)) {
                        candidates.add(new Candidate(name));
                    }
                }
                return;
            }
        }
    }

    // Command implementations
    private static class HelpCommand extends Command {
        HelpCommand() { super("help", "Show available commands", CommandType.LOCAL); }
        @Override public LocalCommandResult executeLocal(String args, com.claude.code.tool.ToolUseContext ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n" + ANSI_BOLD + "Available Commands:" + ANSI_RESET + "\n\n");
            // Will be populated dynamically
            sb.append("  /help     - Show this help\n");
            sb.append("  /clear    - Clear conversation history\n");
            sb.append("  /model    - Change AI model\n");
            sb.append("  /compact  - Compact conversation\n");
            sb.append("  /commit   - Create a git commit\n");
            sb.append("  /exit     - Exit\n");
            return LocalCommandResult.text(sb.toString());
        }
    }

    private static class ClearCommand extends Command {
        ClearCommand() { super("clear", "Clear conversation history", CommandType.LOCAL); }
        @Override public LocalCommandResult executeLocal(String args, com.claude.code.tool.ToolUseContext ctx) {
            return LocalCommandResult.text(ANSI_GREEN + "Conversation cleared." + ANSI_RESET);
        }
    }

    private static class ModelCommand extends Command {
        ModelCommand() { super("model", "Change the AI model", CommandType.LOCAL); }
        @Override
        public LocalCommandResult executeLocal(String args, com.claude.code.tool.ToolUseContext ctx) {
            if (args == null || args.isEmpty()) {
                return LocalCommandResult.text("Current model: claude-sonnet-4-20250514\nUsage: /model <model-name>");
            }
            return LocalCommandResult.text(ANSI_GREEN + "Model set to: " + args + ANSI_RESET);
        }
    }

    private static class CompactCommand extends Command {
        CompactCommand() { super("compact", "Compact conversation history", CommandType.LOCAL); }
        @Override public LocalCommandResult executeLocal(String args, com.claude.code.tool.ToolUseContext ctx) {
            return LocalCommandResult.compact();
        }
    }

    private static class CommitCommand extends Command {
        CommitCommand() { super("commit", "Create a git commit with AI assistance", CommandType.PROMPT); }
        @Override
        public String getPromptForCommand(String args, com.claude.code.tool.ToolUseContext ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append("Create a git commit for the current changes.\n");
            sb.append("Run `git status` and `git diff` to see what changed, then create an appropriate commit message.\n");
            sb.append("Use the Bash tool to run git commands.\n");
            if (args != null && !args.isEmpty()) sb.append("Additional context: ").append(args).append("\n");
            return sb.toString();
        }
    }
}

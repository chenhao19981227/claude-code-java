package com.claude.code.context;

import com.claude.code.util.GitUtil;

import java.util.ArrayList;
import java.util.List;

public class SystemPromptBuilder {
    public static List<String> buildSystemPrompt(String workingDir) {
        List<String> parts = new ArrayList<>();
        parts.add(getClaudeCodeSystemPrompt());
        parts.add(getEnvironmentContext(workingDir));
        return parts;
    }

    private static String getClaudeCodeSystemPrompt() {
        return "You are Claude, an AI assistant by Anthropic. You are operating as Claude Code, an interactive CLI tool that helps users with programming tasks.\n\n" +
               "You have access to tools that let you read files, edit files, run shell commands, search code, and manage tasks.\n\n" +
               "Key behaviors:\n" +
               "- Always read files before editing them\n" +
               "- Use Glob to find files and Grep to search content\n" +
               "- Run commands with Bash tool to build, test, and run code\n" +
               "- Be concise in responses - don't explain unless asked\n" +
               "- When done, summarize what you did\n" +
               "- If something is unclear, ask the user\n";
    }

    private static String getEnvironmentContext(String workingDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("<environment_details>\n");
        sb.append("  <working_directory>").append(workingDir).append("</working_directory>\n");

        try {
            String branch = GitUtil.getCurrentBranch(workingDir);
            if (branch != null) sb.append("  <git_branch>").append(branch).append("</git_branch>\n");
            boolean isGit = GitUtil.isGitRepo(workingDir);
            sb.append("  <is_git_repo>").append(isGit).append("</is_git_repo>\n");
            String os = System.getProperty("os.name");
            sb.append("  <os>").append(os).append("</os>\n");
            sb.append("  <java_version>").append(System.getProperty("java.version")).append("</java_version>\n");
        } catch (Exception e) {
            sb.append("  <git_branch>unknown</git_branch>\n");
        }

        sb.append("</environment_details>");
        return sb.toString();
    }
}

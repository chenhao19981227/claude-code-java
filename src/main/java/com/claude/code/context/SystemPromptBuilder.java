package com.claude.code.context;

import com.claude.code.config.AppProperties;
import com.claude.code.skill.Skill;
import com.claude.code.util.GitUtil;

import java.util.ArrayList;
import java.util.List;

public class SystemPromptBuilder {
    public static List<String> buildSystemPrompt(String workingDir, AppProperties settings, List<Skill> relevantSkills) {
        var parts = new ArrayList<String>();
        parts.add(getSystemPrompt(settings));
        parts.add(getEnvironmentContext(workingDir));
        if (relevantSkills != null && !relevantSkills.isEmpty()) {
            parts.add(getSkillsSection(relevantSkills));
        }
        return parts;
    }

    private static String getSystemPrompt(AppProperties settings) {
        String provider = settings != null ? settings.getProvider() : "unknown";
        String model = settings != null ? settings.getEffectiveModel() : "unknown";
        return """
                You are an AI coding assistant powered by %s (%s). You are operating as Claude Code, an interactive CLI tool that helps users with programming tasks.
                
                You have access to tools that let you read files, edit files, run shell commands, search code, and manage tasks.
                
                Key behaviors:
                - Always read files before editing them
                - Use Glob to find files and Grep to search content
                - Run commands with Bash tool to build, test, and run code
                - Be concise in responses - don't explain unless asked
                - When done, summarize what you did
                - If something is unclear, ask the user
                - When asked about your identity, honestly state you are %s by %s, not Claude or Anthropic
                """.formatted(model, provider, model, provider);
    }

    private static String getEnvironmentContext(String workingDir) {
        var sb = new StringBuilder();
        sb.append("<environment_details>\n");
        sb.append("  <working_directory>").append(workingDir).append("</working_directory>\n");

        try {
            String branch = GitUtil.getCurrentBranch(workingDir);
            if (branch != null) sb.append("  <git_branch>").append(branch).append("</git_branch>\n");
            sb.append("  <is_git_repo>").append(GitUtil.isGitRepo(workingDir)).append("</is_git_repo>\n");
            sb.append("  <os>").append(System.getProperty("os.name")).append("</os>\n");
            sb.append("  <java_version>").append(System.getProperty("java.version")).append("</java_version>\n");
        } catch (Exception e) {
            sb.append("  <git_branch>unknown</git_branch>\n");
        }

        sb.append("</environment_details>");
        return sb.toString();
    }

    private static String getSkillsSection(List<Skill> skills) {
        var sb = new StringBuilder();
        sb.append("\n## Active Skills\n");
        for (var skill : skills) {
            sb.append("\n### ").append(skill.name()).append("\n");
            sb.append(skill.content()).append("\n");
        }
        return sb.toString();
    }
}

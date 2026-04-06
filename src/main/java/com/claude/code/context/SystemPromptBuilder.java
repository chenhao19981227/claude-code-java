package com.claude.code.context;

import com.claude.code.config.AppProperties;
import com.claude.code.skill.Skill;
import com.claude.code.skill.SkillMatcher;
import com.claude.code.tool.Tool;
import com.claude.code.tool.ToolRegistry;
import com.claude.code.util.GitUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SystemPromptBuilder {
    public static List<String> buildSystemPrompt(String workingDir, AppProperties settings,
                                                  List<Skill> activeSkills, ToolRegistry toolRegistry,
                                                  String mode, SkillMatcher skillMatcher,
                                                  Map<String, Skill> skillMap) {
        var parts = new ArrayList<String>();
        parts.add(getSystemPrompt(settings, mode));
        parts.add(getToolSection(toolRegistry));
        parts.add(getEnvironmentContext(workingDir));
        // List available slash commands so the model knows what skills exist
        if (skillMatcher != null && skillMap != null && !skillMap.isEmpty()) {
            parts.add(skillMatcher.buildCommandList(skillMap));
        }
        // Inject active skill content (from slash command or explicit activation)
        if (activeSkills != null && !activeSkills.isEmpty()) {
            parts.add(getActiveSkillSection(activeSkills));
        }
        return parts;
    }

    private static String getSystemPrompt(AppProperties settings, String mode) {
        String provider = settings != null ? settings.getProvider() : "unknown";
        String model = settings != null ? settings.getEffectiveModel() : "unknown";
        String modeInstruction = switch (mode != null ? mode : "build") {
            case "plan" -> """

                    You are currently in **PLAN MODE** (read-only).
                    - You can ONLY use Read, Glob, Grep, TodoWrite tools.
                    - You CANNOT use Write, Edit, or Bash tools — any attempt will be denied.
                    - Focus on understanding the codebase, planning approaches, and answering questions.
                    - When you have a plan ready, tell the user to switch to Build mode to execute.""";
            default -> """

                    You are currently in **BUILD MODE**.
                    - Write and edit operations require user confirmation.
                    - Bash commands require user confirmation.
                    - Read-only tools (Read, Glob, Grep, TodoWrite) are auto-approved.
                    - When the user approves "Allow All" for a tool, subsequent uses of that tool are auto-approved for this session.""";
        };
        return """
                You are an AI coding assistant powered by %s (%s). You help users with programming tasks.

                ## Core Rules
                - Respond to the user's CURRENT message. Do NOT repeat or continue previous tasks unless explicitly asked.
                - Only use tools when the current user message requires it. If the user asks a question, just answer it.
                - Do NOT re-execute tools from previous turns. Previous tool calls already have results in the conversation.
                - Read the conversation history carefully — if a tool was already called and produced a result, do not call it again.
                - If the user mentions a specific project, directory, or file path, read THAT location — do NOT assume they are asking about the working directory.
                - The working directory in environment_details is just the default starting point. The user may ask about ANY project or path on the system.
                - Be concise — don't explain unless asked
                - When done, summarize what you did
                - If something is unclear, ask the user
                - When asked about your identity, honestly state you are %s by %s, not Claude or Anthropic

                ## Available Tools
                - **Read**: Read file contents (auto-approved)
                - **Write**: Create NEW files (needs approval in build mode)
                - **Edit**: Modify existing files — find and replace strings (needs approval in build mode)
                - **Bash**: Execute shell commands — git, npm, build, test, etc. (needs approval in build mode)
                - **Glob**: Find files by name pattern (auto-approved)
                - **Grep**: Search file contents with regex (auto-approved)
                - **TodoWrite**: Track task progress with a todo list (auto-approved)

                ## Tool Usage Guidelines
                - ALWAYS read a file before editing it
                - Use Glob to find files and Grep to search content
                - Use Write to create new files, Edit to modify existing files
                - Use Bash for build, test, git, and other CLI commands
                - If the user sends a simple question or command like listing something, answering a question, or changing settings — respond directly WITHOUT using any tools
                %s""".formatted(model, provider, model, provider, modeInstruction);
    }

    private static String getToolSection(ToolRegistry toolRegistry) {
        if (toolRegistry == null) return "";
        var tools = toolRegistry.getEnabledTools();
        if (tools.isEmpty()) return "";
        var sb = new StringBuilder("\n## Tool Definitions\n");
        for (var tool : tools) {
            sb.append("\n### ").append(tool.getName()).append("\n");
            sb.append(tool.getDescription()).append("\n");
            sb.append("Schema: ").append(tool.getInputSchemaJson()).append("\n");
        }
        return sb.toString();
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

        sb.append("</environment_details>\n");
        sb.append("NOTE: The working_directory above is just the default starting point. ");
        sb.append("The user may ask about any project or path on the system. Always follow the user's intent.\n");
        return sb.toString();
    }

    public static String getActiveSkillSection(List<Skill> skills) {
        var sb = new StringBuilder();
        sb.append("\n## Active Skill\n");
        sb.append("The following skill has been activated. Follow its instructions carefully.\n");
        for (var skill : skills) {
            sb.append("\n### Skill: ").append(skill.getName()).append("\n");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append("> ").append(skill.getDescription()).append("\n\n");
            }
            sb.append(skill.getContent()).append("\n");
        }
        return sb.toString();
    }
}

package com.claude.code.skill;

import java.util.*;

/**
 * Matches user input to skills using Claude Code's approach:
 *
 * 1. **Slash command direct trigger**: User types "/skillname" or "/skillname arg"
 *    → Directly returns the matching skill.
 *
 * 2. **Model auto-invocation**: Claude sees skill descriptions in the system prompt
 *    and can invoke a skill via the Skill tool when it determines one is relevant.
 *    This is the Claude Code approach — the MODEL decides, not a keyword algorithm.
 *
 * Control (from frontmatter):
 * - `disable-model-invocation: true` → skill NOT listed for model, user-only
 * - `user-invocable: false` → skill NOT listed for user, model-only
 * - Default → both can invoke, description always in system prompt
 */
public class SkillMatcher {

    /**
     * Check if the user message is a slash command targeting a specific skill.
     * Only matches skills where userInvocable is true.
     */
    public Optional<Skill> matchSlashCommand(String userMessage, Map<String, Skill> skillMap) {
        if (userMessage == null || userMessage.trim().isEmpty()) return Optional.empty();
        String trimmed = userMessage.trim();
        if (!trimmed.startsWith("/")) return Optional.empty();

        String[] parts = trimmed.substring(1).split("\\s+", 2);
        String commandName = parts[0].toLowerCase();

        Skill skill = skillMap.get(commandName);
        if (skill != null && skill.isUserInvocable()) {
            return Optional.of(skill);
        }
        return Optional.empty();
    }

    /**
     * Build the skill listing for the system prompt.
     * Only includes skills where `disable-model-invocation` is NOT true.
     * Claude sees these descriptions and can auto-invoke via the Skill tool.
     */
    public String buildCommandList(Map<String, Skill> skillMap) {
        if (skillMap == null || skillMap.isEmpty()) return "";

        var visibleSkills = skillMap.values().stream()
                .filter(Skill::isVisibleToModel)
                .sorted(Comparator.comparing(Skill::getName))
                .toList();

        if (visibleSkills.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("## Available Skills\n\n");
        sb.append("You can invoke a skill by calling the `Skill` tool with the skill name. ");
        sb.append("The user can also type `/skill-name` directly.\n\n");

        for (var skill : visibleSkills) {
            sb.append("- **").append(skill.getName()).append("**");
            if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
                sb.append(": ").append(skill.getDescription());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Build the slash command listing for user-facing autocomplete/menu.
     * Only includes skills where `user-invocable` is true.
     */
    public List<String> getUserCommandList(Map<String, Skill> skillMap) {
        if (skillMap == null) return List.of();
        return skillMap.values().stream()
                .filter(Skill::isUserInvocable)
                .map(s -> "/" + s.getName())
                .sorted()
                .toList();
    }
}

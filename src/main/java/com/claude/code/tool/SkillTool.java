package com.claude.code.tool;

import com.claude.code.skill.Skill;
import com.claude.code.skill.SkillLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill tool — allows the model to invoke skills dynamically.
 *
 * This is how Claude Code's auto-invocation works:
 * 1. Skill descriptions are listed in the system prompt
 * 2. The model determines a skill is relevant to the current task
 * 3. The model calls this Skill tool to load the skill content
 * 4. The skill content is injected into the conversation context
 *
 * The loaded skill is stored in ToolUseContext for the QueryEngine to pick up
 * and inject into the next API call's system prompt.
 */
@Component
public class SkillTool extends Tool {
    private static final Logger log = LoggerFactory.getLogger(SkillTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ThreadLocal to pass the loaded skill back to QueryEngine */
    private static final ThreadLocal<Skill> loadedSkill = new ThreadLocal<>();

    private final SkillLoader skillLoader;

    public SkillTool(SkillLoader skillLoader) {
        super("Skill", "Invoke a skill to load its instructions into the conversation context. "
                + "Use this when you determine a skill from the Available Skills list is relevant to the current task.",
                "invoke skill", 50000, true, true);
        this.skillLoader = skillLoader;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult call(String inputJson, ToolUseContext context) throws Exception {
        Map<String, Object> input = MAPPER.readValue(inputJson, Map.class);
        String skillName = (String) input.get("name");
        String argument = (String) input.get("argument");

        if (skillName == null || skillName.trim().isEmpty()) {
            return ToolResult.error("name is required — specify the skill name to invoke");
        }

        var skillMap = skillLoader.loadAllSkills();
        Skill skill = skillMap.get(skillName.toLowerCase().trim());

        if (skill == null) {
            return ToolResult.error("Unknown skill: " + skillName
                    + ". Available skills: " + String.join(", ", skillMap.keySet()));
        }

        if (!skill.isModelInvocable()) {
            return ToolResult.error("Skill '" + skillName + "' cannot be invoked by the model "
                    + "(disable-model-invocation is set). The user must invoke it via /" + skillName);
        }

        // Store the loaded skill for QueryEngine to pick up
        loadedSkill.set(skill);
        log.info("Model invoked skill: /{} (arg: {})", skill.getName(), argument);

        var data = new HashMap<String, Object>();
        data.put("skill", skill.getName());
        data.put("description", skill.getDescription());
        data.put("status", "loaded");
        if (argument != null && !argument.isEmpty()) {
            data.put("argument", argument);
        }
        return ToolResult.success(data);
    }

    /** Get and clear the loaded skill (called by QueryEngine after tool execution) */
    public static Skill consumeLoadedSkill() {
        Skill skill = loadedSkill.get();
        loadedSkill.remove();
        return skill;
    }

    @Override
    public String getInputSchemaJson() {
        return """
                {"type":"object","properties":{"name":{"type":"string","description":"The skill name to invoke (without /)"},"argument":{"type":"string","description":"Optional argument to pass to the skill"}},"required":["name"]}""";
    }
}

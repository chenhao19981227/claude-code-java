package com.claude.code.skill;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SkillLoader {
    private final List<String> skillDirectories;
    private List<Skill> cachedSkills;

    public SkillLoader(List<String> skillDirectories) {
        this.skillDirectories = skillDirectories != null ? skillDirectories : new ArrayList<String>();
    }

    public List<Skill> loadAllSkills() {
        if (cachedSkills != null) {
            return cachedSkills;
        }
        List<Skill> skills = new ArrayList<Skill>();

        // Always include default skill directory
        List<String> dirs = new ArrayList<String>(skillDirectories);
        boolean hasDefault = false;
        for (String dir : dirs) {
            if ("skills".equals(dir) || ("." + File.separator + "skills").equals(dir)) {
                hasDefault = true;
                break;
            }
        }
        if (!hasDefault) {
            dirs.add("skills");
        }

        for (String dirPath : dirs) {
            File dir = new File(dirPath);
            if (!dir.exists() || !dir.isDirectory()) {
                continue;
            }
            loadSkillsFromDirectory(dir, skills);
        }

        cachedSkills = skills;
        return skills;
    }

    private void loadSkillsFromDirectory(File dir, List<Skill> skills) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File child : children) {
            if (child.isDirectory()) {
                // Check for SKILL.md inside subdirectory
                File skillFile = new File(child, "SKILL.md");
                if (skillFile.exists() && skillFile.canRead()) {
                    Skill skill = parseSkillFile(skillFile, child.getName());
                    if (skill != null) {
                        skills.add(skill);
                    }
                }
                // Recurse into nested directories
                loadSkillsFromDirectory(child, skills);
            } else if ("SKILL.md".equals(child.getName())) {
                // SKILL.md in the root of a skill directory
                Skill skill = parseSkillFile(child, dir.getName());
                if (skill != null) {
                    skills.add(skill);
                }
            }
        }
    }

    private Skill parseSkillFile(File file, String defaultName) {
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String description = extractDescription(content);
            return new Skill(defaultName, description, content, file.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Warning: failed to read skill file " + file.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    private String extractDescription(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String[] lines = content.split("\\r?\\n");
        // Look for a # Description header first
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.toLowerCase().startsWith("# description")) {
                // Take the next non-empty line as the description
                for (int j = i + 1; j < lines.length; j++) {
                    String descLine = lines[j].trim();
                    if (!descLine.isEmpty() && !descLine.startsWith("#")) {
                        return descLine;
                    }
                }
            }
        }

        // Fall back to first non-empty, non-heading line
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                // Truncate long lines
                if (trimmed.length() > 200) {
                    return trimmed.substring(0, 200) + "...";
                }
                return trimmed;
            }
        }

        return "";
    }

    public void reload() {
        cachedSkills = null;
    }
}

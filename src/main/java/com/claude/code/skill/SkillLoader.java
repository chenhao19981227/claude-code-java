package com.claude.code.skill;

import com.claude.code.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Component
public class SkillLoader {
    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private final List<String> skillDirectories;
    private List<Skill> cachedSkills;

    public SkillLoader(AppProperties appProperties) {
        this.skillDirectories = appProperties.getSkillDirectories() != null
                ? appProperties.getSkillDirectories()
                : new ArrayList<>(List.of("skills/"));
    }

    public List<Skill> loadAllSkills() {
        if (cachedSkills != null) return cachedSkills;

        var skills = new ArrayList<Skill>();
        var dirs = new ArrayList<>(skillDirectories);

        // Always include default skill directory
        boolean hasDefault = dirs.stream().anyMatch(d -> "skills".equals(d) || ("." + File.separator + "skills").equals(d));
        if (!hasDefault) dirs.add("skills");

        for (var dirPath : dirs) {
            var dir = new File(dirPath);
            if (dir.exists() && dir.isDirectory()) {
                loadSkillsFromDirectory(dir, skills);
            }
        }

        cachedSkills = skills;
        log.info("Loaded {} skills from {} directories", skills.size(), dirs.size());
        return skills;
    }

    private void loadSkillsFromDirectory(File dir, List<Skill> skills) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (var child : children) {
            if (child.isDirectory()) {
                var skillFile = new File(child, "SKILL.md");
                if (skillFile.exists() && skillFile.canRead()) {
                    parseSkillFile(skillFile, child.getName()).ifPresent(skills::add);
                }
                loadSkillsFromDirectory(child, skills);
            } else if ("SKILL.md".equals(child.getName())) {
                parseSkillFile(child, dir.getName()).ifPresent(skills::add);
            }
        }
    }

    private java.util.Optional<Skill> parseSkillFile(File file, String defaultName) {
        try {
            String content = Files.readString(file.toPath());
            String description = extractDescription(content);
            return java.util.Optional.of(new Skill(defaultName, description, content, file.getAbsolutePath()));
        } catch (Exception e) {
            log.warn("Failed to read skill file {}: {}", file.getAbsolutePath(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private String extractDescription(String content) {
        if (content == null || content.isEmpty()) return "";
        String[] lines = content.split("\\r?\\n");

        for (var line : lines) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().startsWith("# description")) {
                for (var descLine : lines) {
                    String dl = descLine.trim();
                    if (!dl.isEmpty() && !dl.startsWith("#")) return dl.length() > 200 ? dl.substring(0, 200) + "..." : dl;
                }
            }
        }

        for (var line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
            }
        }
        return "";
    }

    public void reload() { cachedSkills = null; }
}

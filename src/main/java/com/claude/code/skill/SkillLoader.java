package com.claude.code.skill;

import com.claude.code.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads skills from SKILL.md files in configured directories.
 *
 * Supports Claude Code compatible YAML frontmatter:
 * ---
 * name: skill-name
 * description: What this skill does
 * scope: builtin | user | project
 * trigger_phrases: phrase1, phrase2
 * ---
 * <markdown body>
 */
@Component
public class SkillLoader {
    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private static final String FRONTMATTER_DELIMITER = "---";

    private final List<String> skillDirectories;
    private volatile Map<String, Skill> skillMap;  // name -> Skill for fast lookup

    public SkillLoader(AppProperties appProperties) {
        this.skillDirectories = appProperties.getSkillDirectories() != null
                ? appProperties.getSkillDirectories()
                : new ArrayList<>(List.of("skills/"));
    }

    /**
     * Load all skills from configured directories.
     * Skills are indexed by name for fast slash-command lookup.
     */
    public Map<String, Skill> loadAllSkills() {
        if (skillMap != null) return skillMap;

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

        // Index by name (user/project skills override builtin with same name)
        var map = new LinkedHashMap<String, Skill>();
        // Load in priority order: builtin first, then user, then project
        List<Skill> sorted = skills.stream()
                .sorted(Comparator.comparingInt(s -> scopePriority(s.getScope())))
                .toList();
        for (var skill : sorted) {
            map.put(skill.getName().toLowerCase(), skill);
        }

        skillMap = Collections.unmodifiableMap(map);
        log.info("Loaded {} skills from {} directories: {}", map.size(), dirs.size(),
                map.values().stream().map(Skill::getName).collect(Collectors.joining(", ")));
        return skillMap;
    }

    /** Get a skill by slash command name (e.g. "/git-master" or "git-master") */
    public Optional<Skill> findByName(String name) {
        if (skillMap == null) loadAllSkills();
        String key = name.toLowerCase();
        if (key.startsWith("/")) key = key.substring(1);
        return Optional.ofNullable(skillMap.get(key));
    }

    /** Get all loaded skills */
    public Collection<Skill> getAllSkills() {
        if (skillMap == null) loadAllSkills();
        return skillMap.values();
    }

    /** Get skill names available as slash commands */
    public List<String> getCommandNames() {
        return getAllSkills().stream()
                .map(s -> "/" + s.getName())
                .sorted()
                .toList();
    }

    public void reload() {
        skillMap = null;
    }

    // --- Frontmatter parsing ---

    private void loadSkillsFromDirectory(File dir, List<Skill> skills) {
        File[] children = dir.listFiles();
        if (children == null) return;

        for (var child : children) {
            if (child.isDirectory()) {
                var skillFile = new File(child, "SKILL.md");
                if (skillFile.exists() && skillFile.canRead()) {
                    parseSkillFile(skillFile, child.getName(), inferScope(dir)).ifPresent(skills::add);
                }
                loadSkillsFromDirectory(child, skills);
            } else if ("SKILL.md".equals(child.getName())) {
                parseSkillFile(child, dir.getName(), inferScope(dir)).ifPresent(skills::add);
            }
        }
    }

    private String inferScope(File dir) {
        String path = dir.getAbsolutePath().toLowerCase();
        if (path.contains("builtin") || path.contains("internal")) return "builtin";
        // User home directory skills
        String userHome = System.getProperty("user.home").toLowerCase();
        if (path.startsWith(userHome) && !path.contains("claude-code-java")) return "user";
        return "project";
    }

    private Optional<Skill> parseSkillFile(File file, String defaultName, String defaultScope) {
        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            var parsed = parseFrontmatter(raw);
            String name = parsed.name() != null ? parsed.name() : defaultName;
            String description = parsed.description();
            String scope = parsed.scope() != null ? parsed.scope() : defaultScope;
            boolean disableModel = parsed.disableModelInvocation();
            boolean userInvocable = parsed.userInvocable();
            List<String> allowedTools = parsed.allowedTools();
            List<String> paths = parsed.paths();
            String content = parsed.body();

            return Optional.of(new Skill(name, description, scope,
                    disableModel, userInvocable, allowedTools, paths,
                    content, file.getAbsolutePath()));
        } catch (Exception e) {
            log.warn("Failed to read skill file {}: {}", file.getAbsolutePath(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse YAML-like frontmatter from SKILL.md content.
     * Returns a record with parsed fields and the body content (after frontmatter).
     */
    private record ParsedFrontmatter(String name, String description, String scope,
                                      boolean disableModelInvocation, boolean userInvocable,
                                      List<String> allowedTools, List<String> paths,
                                      String body) {}

    private ParsedFrontmatter parseFrontmatter(String raw) {
        String[] lines = raw.split("\\r?\\n");

        // Check if file starts with ---
        int bodyStart = 0;
        var frontmatter = new LinkedHashMap<String, String>();

        if (lines.length > 0 && lines[0].trim().equals(FRONTMATTER_DELIMITER)) {
            // Find closing ---
            int end = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals(FRONTMATTER_DELIMITER)) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                // Parse key: value pairs
                for (int i = 1; i < end; i++) {
                    String line = lines[i];
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).trim().toLowerCase();
                        String value = line.substring(colonIdx + 1).trim();
                        frontmatter.put(key, value);
                    }
                }
                bodyStart = end + 1;
            }
        }

        // Extract body (skip leading blank lines)
        while (bodyStart < lines.length && lines[bodyStart].trim().isEmpty()) {
            bodyStart++;
        }
        String body = bodyStart < lines.length
                ? String.join("\n", Arrays.copyOfRange(lines, bodyStart, lines.length))
                : "";

        String name = frontmatter.get("name");
        String description = frontmatter.get("description");
        String scope = frontmatter.get("scope");

        boolean disableModel = "true".equalsIgnoreCase(frontmatter.get("disable-model-invocation"));
        boolean userInvocable = !"false".equalsIgnoreCase(frontmatter.get("user-invocable"));

        // Parse space-separated lists (allowed-tools, paths)
        List<String> allowedTools = parseStringList(frontmatter.get("allowed-tools"));
        List<String> paths = parseStringList(frontmatter.get("paths"));

        return new ParsedFrontmatter(name, description, scope, disableModel, userInvocable,
                allowedTools, paths, body);
    }

    private List<String> parseStringList(String value) {
        if (value == null || value.isEmpty()) return List.of();
        return Arrays.stream(value.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private int scopePriority(String scope) {
        return switch (scope != null ? scope : "project") {
            case "builtin" -> 0;
            case "user" -> 1;
            default -> 2;  // project
        };
    }
}

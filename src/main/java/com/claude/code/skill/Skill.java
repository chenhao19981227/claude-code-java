package com.claude.code.skill;

public class Skill {
    private final String name;
    private final String description;
    private final String content;
    private final String sourcePath;

    public Skill(String name, String description, String content, String sourcePath) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.sourcePath = sourcePath;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getContent() { return content; }
    public String getSourcePath() { return sourcePath; }
}

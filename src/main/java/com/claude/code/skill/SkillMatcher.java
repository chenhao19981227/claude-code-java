package com.claude.code.skill;

import java.util.*;
import java.util.stream.Collectors;

public class SkillMatcher {
    private static final int MAX_MATCHING_SKILLS = 3;
    private static final double TRIGGER_PHRASE_BOOST = 3.0;

    public List<Skill> findRelevantSkills(String userMessage, List<Skill> allSkills) {
        if (userMessage == null || userMessage.trim().isEmpty() || allSkills == null || allSkills.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> messageTokens = tokenize(userMessage.toLowerCase());
        var scored = new ArrayList<ScoredSkill>();

        for (var skill : allSkills) {
            double score = computeScore(skill, messageTokens, userMessage);
            if (score > 0) {
                scored.add(new ScoredSkill(skill, score));
            }
        }

        scored.sort(Comparator.reverseOrder());
        return scored.stream()
                .limit(MAX_MATCHING_SKILLS)
                .map(s -> s.skill)
                .collect(Collectors.toList());
    }

    private double computeScore(Skill skill, Set<String> messageTokens, String userMessage) {
        double score = 0.0;

        for (var kw : tokenize(skill.name().toLowerCase())) {
            if (messageTokens.contains(kw)) score += 2.0;
        }

        for (var kw : tokenize(skill.description().toLowerCase())) {
            if (messageTokens.contains(kw)) score += 1.0;
        }

        for (var phrase : extractTriggerPhrases(skill.content())) {
            if (userMessage.toLowerCase().contains(phrase.toLowerCase())) {
                score += TRIGGER_PHRASE_BOOST;
            }
        }

        return score;
    }

    private List<String> extractTriggerPhrases(String content) {
        var phrases = new ArrayList<String>();
        if (content == null) return phrases;

        String[] lines = content.split("\\r?\\n");
        boolean inTriggerSection = false;

        for (var line : lines) {
            String trimmed = line.trim().toLowerCase();

            if (trimmed.startsWith("trigger phrases:") || trimmed.startsWith("trigger phrases：")
                    || trimmed.startsWith("触发词:") || trimmed.startsWith("触发词：")) {
                inTriggerSection = true;
                addPhrases(line.substring(line.indexOf(':') + 1).trim(), phrases);
                continue;
            }

            if (inTriggerSection) {
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    inTriggerSection = false;
                    continue;
                }
                if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    phrases.add(line.substring(2).trim());
                } else {
                    addPhrases(trimmed, phrases);
                }
            }
        }
        return phrases;
    }

    private void addPhrases(String text, List<String> phrases) {
        if (text == null || text.isEmpty()) return;
        for (var part : text.split("[,;|]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) phrases.add(trimmed);
        }
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Set.of();
        return Arrays.stream(text.split("[^a-z0-9]+"))
                .filter(p -> p.length() >= 2)
                .collect(Collectors.toSet());
    }

    private record ScoredSkill(Skill skill, double score) implements Comparable<ScoredSkill> {
        @Override
        public int compareTo(ScoredSkill other) { return Double.compare(other.score, this.score); }
    }
}

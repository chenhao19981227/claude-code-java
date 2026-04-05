package com.claude.code.skill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SkillMatcher {
    private static final int MAX_MATCHING_SKILLS = 3;
    private static final double TRIGGER_PHRASE_BOOST = 3.0;

    public List<Skill> findRelevantSkills(String userMessage, List<Skill> allSkills) {
        if (userMessage == null || userMessage.trim().isEmpty() || allSkills == null || allSkills.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> messageTokens = tokenize(userMessage.toLowerCase());
        List<ScoredSkill> scored = new ArrayList<ScoredSkill>();

        for (Skill skill : allSkills) {
            double score = computeScore(skill, messageTokens, userMessage);
            if (score > 0) {
                scored.add(new ScoredSkill(skill, score));
            }
        }

        Collections.sort(scored);
        List<Skill> result = new ArrayList<Skill>();
        int count = Math.min(scored.size(), MAX_MATCHING_SKILLS);
        for (int i = 0; i < count; i++) {
            result.add(scored.get(i).skill);
        }
        return result;
    }

    private double computeScore(Skill skill, Set<String> messageTokens, String userMessage) {
        double score = 0.0;

        // Extract keywords from name
        Set<String> nameKeywords = tokenize(skill.getName().toLowerCase());
        for (String kw : nameKeywords) {
            if (messageTokens.contains(kw)) {
                score += 2.0;
            }
        }

        // Extract keywords from description
        Set<String> descKeywords = tokenize(skill.getDescription().toLowerCase());
        for (String kw : descKeywords) {
            if (messageTokens.contains(kw)) {
                score += 1.0;
            }
        }

        // Check trigger phrases
        List<String> triggerPhrases = extractTriggerPhrases(skill.getContent());
        for (String phrase : triggerPhrases) {
            String lowerPhrase = phrase.toLowerCase();
            if (userMessage.toLowerCase().contains(lowerPhrase)) {
                score += TRIGGER_PHRASE_BOOST;
            }
        }

        return score;
    }

    private List<String> extractTriggerPhrases(String content) {
        List<String> phrases = new ArrayList<String>();
        if (content == null) return phrases;

        String[] lines = content.split("\\r?\\n");
        boolean inTriggerSection = false;

        for (String line : lines) {
            String trimmed = line.trim().toLowerCase();

            if (trimmed.startsWith("trigger phrases:") || trimmed.startsWith("trigger phrases：")
                    || trimmed.startsWith("触发词:") || trimmed.startsWith("触发词：")) {
                inTriggerSection = true;
                // Extract phrases from same line after the colon
                String afterColon = line.substring(line.indexOf(':') + 1).trim();
                addPhrases(afterColon, phrases);
                continue;
            }

            if (inTriggerSection) {
                if (trimmed.startsWith("#") || trimmed.isEmpty()) {
                    inTriggerSection = false;
                    continue;
                }
                if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    String phrase = line.substring(2).trim();
                    phrases.add(phrase);
                } else {
                    addPhrases(trimmed, phrases);
                }
            }
        }

        return phrases;
    }

    private void addPhrases(String text, List<String> phrases) {
        if (text == null || text.isEmpty()) return;
        // Split by comma, semicolon, or pipe
        String[] parts = text.split("[,;|]");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                phrases.add(trimmed);
            }
        }
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<String>();
        if (text == null || text.isEmpty()) return tokens;
        // Split on non-alphanumeric characters, filter short tokens
        String[] parts = text.split("[^a-z0-9]+");
        for (String part : parts) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static class ScoredSkill implements Comparable<ScoredSkill> {
        final Skill skill;
        final double score;

        ScoredSkill(Skill skill, double score) {
            this.skill = skill;
            this.score = score;
        }

        @Override
        public int compareTo(ScoredSkill other) {
            // Higher score first
            return Double.compare(other.score, this.score);
        }
    }
}

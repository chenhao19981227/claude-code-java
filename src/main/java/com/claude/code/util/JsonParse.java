package com.claude.code.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class JsonParse {
    public static java.util.Map<String, Object> simpleParse(String json) {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        if (json == null) return map;
        json = json.trim();
        if (!json.startsWith("{")) return map;
        try {
            json = json.substring(1, json.length() - 1);
            StringBuilder keyBuilder = new StringBuilder();
            StringBuilder valueBuilder = new StringBuilder();
            boolean inKey = true;
            boolean inString = false;
            boolean escaped = false;
            int depth = 0;
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                if (escaped) { if (inKey) keyBuilder.append(c); else valueBuilder.append(c); escaped = false; continue; }
                if (c == '\\') { escaped = true; if (inKey) keyBuilder.append(c); else valueBuilder.append(c); continue; }
                if (c == '"' && depth == 0) { inString = !inString; if (inKey) keyBuilder.append(c); else valueBuilder.append(c); continue; }
                if (!inString) {
                    if (c == '{' || c == '[') depth++;
                    if (c == '}' || c == ']') depth--;
                    if (c == ':' && depth == 0 && inKey) { inKey = false; continue; }
                    if (c == ',' && depth == 0) {
                        putValue(map, keyBuilder.toString().trim(), valueBuilder.toString().trim());
                        keyBuilder.setLength(0); valueBuilder.setLength(0); inKey = true; continue;
                    }
                }
                if (inKey) keyBuilder.append(c); else valueBuilder.append(c);
            }
            if (keyBuilder.length() > 0 && valueBuilder.length() > 0) {
                putValue(map, keyBuilder.toString().trim(), valueBuilder.toString().trim());
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static void putValue(java.util.Map<String, Object> map, String key, String value) {
        key = key.replace("\"", "");
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
            map.put(key, value);
        } else if (value.equals("true") || value.equals("false")) {
            map.put(key, Boolean.parseBoolean(value));
        } else if (value.equals("null")) {
            map.put(key, null);
        } else {
            try { map.put(key, Integer.parseInt(value)); } catch (NumberFormatException e) {
                try { map.put(key, Long.parseLong(value)); } catch (NumberFormatException e2) {
                    try { map.put(key, Double.parseDouble(value)); } catch (NumberFormatException e3) { map.put(key, value); }
                }
            }
        }
    }
}

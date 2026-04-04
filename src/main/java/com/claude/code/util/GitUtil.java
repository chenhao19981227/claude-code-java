package com.claude.code.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class GitUtil {
    public static boolean isGitRepo(String dir) {
        return new File(dir, ".git").exists() || new File(dir, ".git").isDirectory();
    }

    public static String getCurrentBranch(String dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(new File(dir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String branch = reader.readLine();
            p.waitFor();
            return branch != null ? branch.trim() : null;
        } catch (Exception e) { return null; }
    }

    public static String getStatus(String dir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--short");
            pb.directory(new File(dir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public static String getDiff(String dir, int maxLines) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--stat");
            pb.directory(new File(dir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < maxLines) {
                sb.append(line).append("\n");
                count++;
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}

package com.claude.code;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableAsync
public class ClaudeCodeApplication {

    public static void main(String[] args) {
        // Ensure data directory exists for SQLite
        Path dataDir = Path.of(System.getProperty("user.dir"), "data");
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            System.err.println("Failed to create data directory: " + dataDir);
        }
        SpringApplication.run(ClaudeCodeApplication.class, args);
    }
}

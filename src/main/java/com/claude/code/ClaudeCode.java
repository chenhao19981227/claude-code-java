package com.claude.code;

import com.claude.code.cli.Repl;
import com.claude.code.query.QueryEngine;
import com.claude.code.state.Settings;

public class ClaudeCode {
    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        String model = System.getenv("CLAUDE_MODEL");

        Settings settings = Settings.load();

        // Parse args
        for (int i = 0; i < args.length; i++) {
            if ("--model".equals(args[i]) && i + 1 < args.length) {
                model = args[++i];
            } else if ("--version".equals(args[i]) || "-v".equals(args[i])) {
                System.out.println("claude-code-java " + VERSION);
                return;
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printUsage();
                return;
            }
        }

        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Error: ANTHROPIC_API_KEY environment variable is required.");
            System.err.println("Set it with: export ANTHROPIC_API_KEY=sk-ant-...");
            System.err.println("Get one at: https://console.anthropic.com/");
            System.exit(1);
        }

        if (model == null || model.isEmpty()) {
            model = settings.getMainModel();
        }

        String workingDir = System.getProperty("user.dir");
        QueryEngine engine = new QueryEngine(apiKey, model, workingDir);
        Repl repl = new Repl(engine);
        repl.start();
    }

    private static void printUsage() {
        System.out.println("Claude Code Java v" + VERSION);
        System.out.println("Usage: java -jar claude-code-java.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --model <model>    Set the AI model (default: claude-sonnet-4-20250514)");
        System.out.println("  --version, -v      Show version");
        System.out.println("  --help, -h         Show this help");
        System.out.println();
        System.out.println("Environment:");
        System.out.println("  ANTHROPIC_API_KEY   Your Anthropic API key (required)");
        System.out.println("  CLAUDE_MODEL        Default model to use");
        System.out.println("  ANTHROPIC_BASE_URL  Custom API base URL");
    }
}

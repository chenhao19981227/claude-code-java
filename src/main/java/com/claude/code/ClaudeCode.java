package com.claude.code;

import com.claude.code.api.ApiClient;
import com.claude.code.api.ApiProvider;
import com.claude.code.api.AnthropicClient;
import com.claude.code.api.OpenAiCompatibleClient;
import com.claude.code.cli.Repl;
import com.claude.code.query.QueryEngine;
import com.claude.code.state.Settings;

public class ClaudeCode {
    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        Settings settings = Settings.load();

        // Parse args (override config)
        for (int i = 0; i < args.length; i++) {
            if ("--model".equals(args[i]) && i + 1 < args.length) {
                settings.setMainModel(args[++i]);
            } else if ("--provider".equals(args[i]) && i + 1 < args.length) {
                settings.setProvider(args[++i]);
            } else if ("--version".equals(args[i]) || "-v".equals(args[i])) {
                System.out.println("claude-code-java " + VERSION);
                return;
            } else if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                printUsage(settings);
                return;
            }
        }

        if (!settings.hasApiKey()) {
            System.err.println("Error: API key not configured.");
            System.err.println();
            System.err.println("Edit config file: " + Settings.getConfigPath());
            System.err.println();
            System.err.println("Example config:");
            System.err.println("  {");
            System.err.println("    \"provider\": \"glm\",");
            System.err.println("    \"apiKey\": \"your-api-key-here\",");
            System.err.println("    \"mainModel\": \"glm-4\"");
            System.err.println("  }");
            System.err.println();
            System.err.println("Supported providers: glm, deepseek, openai, anthropic");
            System.exit(1);
        }

        // Create API client based on config
        String providerName = settings.getProvider();
        String apiKey = settings.getApiKey();
        String model = settings.getEffectiveModel();
        ApiProvider provider = ApiProvider.fromString(providerName);

        ApiClient apiClient;
        // Let client auto-resolve baseUrl from model when user didn't set custom one
        String baseUrl = settings.getBaseUrl();
        boolean hasCustomBaseUrl = baseUrl != null && !baseUrl.trim().isEmpty();
        String resolvedBaseUrl = hasCustomBaseUrl ? baseUrl : null;
        if (provider == ApiProvider.ANTHROPIC) {
            apiClient = new AnthropicClient(apiKey, model, resolvedBaseUrl);
        } else {
            apiClient = new OpenAiCompatibleClient(apiKey, model, provider, resolvedBaseUrl);
        }

        System.out.println("Provider: " + provider.getTitle() + " | Model: " + model);

        String workingDir = System.getProperty("user.dir");
        QueryEngine engine = new QueryEngine(apiClient, workingDir, settings);
        Repl repl = new Repl(engine);
        repl.start();
    }

    private static void printUsage(Settings settings) {
        System.out.println("Claude Code Java v" + VERSION);
        System.out.println();
        System.out.println("Usage: java -jar claude-code-java.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --model <model>      Override model (config: mainModel)");
        System.out.println("  --provider <name>    Override provider (config: provider)");
        System.out.println("  --version, -v        Show version");
        System.out.println("  --help, -h           Show this help");
        System.out.println();
        System.out.println("Config file: " + Settings.getConfigPath());
        System.out.println();
        System.out.println("Config fields:");
        System.out.println("  provider       LLM provider: glm, deepseek, openai, anthropic (default: glm)");
        System.out.println("  apiKey         API key for the provider");
        System.out.println("  baseUrl        Custom API base URL (optional, auto-detected from provider)");
        System.out.println("  mainModel      Model name (default varies by provider)");
        System.out.println("  smallModel     Model for small tasks");
        System.out.println("  maxTokens      Max tokens per request (default: 8192)");
        System.out.println("  temperature    Sampling temperature (default: 0.7)");
        System.out.println("  systemPrompt   Custom system prompt (optional)");
        System.out.println("  customInstructions  Extra instructions appended to system prompt");
        System.out.println();
        System.out.println("Example config.json:");
        System.out.println("  {");
        System.out.println("    \"provider\": \"glm\",");
        System.out.println("    \"apiKey\": \"your-key-here\",");
        System.out.println("    \"mainModel\": \"glm-4\",");
        System.out.println("    \"maxTokens\": 8192");
        System.out.println("  }");
    }
}

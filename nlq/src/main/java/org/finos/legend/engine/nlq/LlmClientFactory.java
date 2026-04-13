package org.finos.legend.engine.nlq;

/**
 * Factory for creating LLM clients based on environment configuration.
 *
 * Environment variables:
 * - LLM_PROVIDER: "anthropic-cli" (default), "anthropic-api", "gemini"
 *
 * Provider details:
 * - "anthropic-cli"  : Uses `claude -p ...` subprocess. Requires Claude Pro/Max subscription.
 *                      No API key needed — uses your authenticated claude CLI session.
 * - "anthropic-api"  : Uses Anthropic REST API. Requires ANTHROPIC_API_KEY env var.
 * - "gemini"         : Uses Google Gemini API. Requires GEMINI_API_KEY env var.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    /**
     * Creates an LLM client based on the LLM_PROVIDER env var.
     * Defaults to anthropic-cli (Claude Pro/Max subscription via CLI) if not specified.
     */
    public static LlmClient create() {
        return create((String) null);
    }

    /**
     * Creates an LLM client with an optional model override.
     * Provider is determined from LLM_PROVIDER env var (defaults to anthropic-cli).
     *
     * @param model optional model string (e.g. "claude-haiku-4-5"); null = use provider default
     */
    public static LlmClient create(String model) {
        String provider = System.getenv("LLM_PROVIDER");
        if (provider == null || provider.isBlank()) {
            // Fall back to system property (populated by NlqHttpServer.loadDotEnv when .env present)
            provider = System.getProperty("LLM_PROVIDER");
        }
        if (provider == null || provider.isBlank()) {
            provider = "anthropic-cli";
        }

        return switch (provider.toLowerCase()) {
            case "anthropic-cli", "anthropic" ->
                    (model != null && !model.isBlank()) ? new AnthropicCliClient(model) : new AnthropicCliClient();
            case "anthropic-api" -> new AnthropicApiClient(null, model);
            case "gemini" -> new GeminiClient(null, model);
            default -> throw new LlmClient.LlmException(
                    "Unsupported LLM provider: " + provider +
                    ". Supported: anthropic-cli, anthropic-api, gemini. Set LLM_PROVIDER env var.", -1, null);
        };
    }

    /**
     * Creates an LLM client with explicit provider + API key.
     */
    public static LlmClient create(String provider, String apiKey) {
        return switch (provider.toLowerCase()) {
            case "anthropic-cli", "anthropic" -> new AnthropicCliClient();
            case "anthropic-api" -> new AnthropicApiClient(apiKey, null);
            case "gemini" -> new GeminiClient(apiKey, null);
            default -> throw new LlmClient.LlmException(
                    "Unsupported LLM provider: " + provider, -1, null);
        };
    }
}

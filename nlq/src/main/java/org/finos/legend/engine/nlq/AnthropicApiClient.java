package org.finos.legend.engine.nlq;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM client for Anthropic REST API (Claude).
 * Uses java.net.http.HttpClient — no external dependencies.
 *
 * Requires ANTHROPIC_API_KEY environment variable.
 * Use LLM_PROVIDER=anthropic-api to select this client.
 */
public class AnthropicApiClient implements LlmClient {

    private static final String DEFAULT_MODEL = "claude-opus-4-6";
    private static final String BASE_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public AnthropicApiClient() {
        // Check env var first, then system property (set by NlqHttpServer.loadDotEnv)
        this(resolveApiKey(), DEFAULT_MODEL);
    }

    private static String resolveApiKey() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("ANTHROPIC_API_KEY");
        }
        return key;
    }

    public AnthropicApiClient(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("ANTHROPIC_API_KEY environment variable is not set", -1, null);
        }
        this.apiKey = apiKey;
        this.model = model != null ? model : DEFAULT_MODEL;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 4000;

    @Override
    public String complete(String systemPrompt, String userMessage) {
        String requestBody = buildRequestBody(systemPrompt, userMessage);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .timeout(TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                long t0 = System.nanoTime();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("      [AnthropicAPI] attempt=%d status=%d %dms prompt=%dchars%n",
                        attempt, response.statusCode(), ms, requestBody.length());
                System.out.flush();

                if ((response.statusCode() == 429 || response.statusCode() == 529 || response.statusCode() == 503) && attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                    System.out.printf("      [AnthropicAPI] %d, backing off %ds%n", response.statusCode(), backoff / 1000);
                    System.out.flush();
                    try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                    continue;
                }

                if (response.statusCode() != 200) {
                    throw new LlmException(
                            "Anthropic API returned " + response.statusCode(),
                            response.statusCode(),
                            response.body());
                }

                return extractText(response.body());
            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                    System.out.printf("      [AnthropicAPI] timeout/error, backing off %ds: %s%n", backoff / 1000, e.getMessage());
                    System.out.flush();
                    try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                    continue;
                }
                throw new LlmException("Anthropic API request failed: " + e.getMessage(), e);
            }
        }
        throw new LlmException("Anthropic API: max retries exhausted", 429, null);
    }

    @Override
    public String provider() {
        return "anthropic-api";
    }

    @Override
    public String model() {
        return model;
    }

    // ==================== JSON helpers (no external deps) ====================

    private String buildRequestBody(String systemPrompt, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":").append(escapeJson(model)).append(",");
        sb.append("\"max_tokens\":4096,");
        sb.append("\"temperature\":0.1,");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("\"system\":").append(escapeJson(systemPrompt)).append(",");
        }

        sb.append("\"messages\":[{\"role\":\"user\",\"content\":")
          .append(escapeJson(userMessage))
          .append("}]");

        sb.append("}");
        return sb.toString();
    }

    /**
     * Extracts text from Anthropic response JSON.
     * Expected path: content[0].text
     */
    private static String extractText(String json) {
        Pattern p = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return unescapeJson(m.group(1));
        }
        throw new LlmException("Could not extract text from Anthropic response", -1, json);
    }

    static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '/' -> { sb.append('/'); i++; }
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            String hex = s.substring(i + 2, i + 6);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 5;
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

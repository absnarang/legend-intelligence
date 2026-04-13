package org.finos.legend.engine.nlq;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM client that uses the Claude CLI (claude -p ...) via subprocess.
 * Works with Claude Pro/Max subscription — no API key required.
 *
 * Requires the `claude` CLI to be installed and authenticated.
 * Use LLM_PROVIDER=anthropic-cli (or just "anthropic") to select this client.
 */
public class AnthropicCliClient implements LlmClient {

    private static final String DEFAULT_MODEL = "claude-haiku-4-5";
    private static final int TIMEOUT_SECONDS = 180;

    private final String model;

    public AnthropicCliClient() {
        this(DEFAULT_MODEL);
    }

    public AnthropicCliClient(String model) {
        this.model = model != null ? model : DEFAULT_MODEL;
    }

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 3000;

    @Override
    public String complete(String systemPrompt, String userMessage) {
        // Combine system prompt + user message into a single prompt, same pattern as playground.py
        String fullPrompt = (systemPrompt != null && !systemPrompt.isBlank())
                ? systemPrompt + "\n\n---\n\n" + userMessage
                : userMessage;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<String> cmd = new ArrayList<>(List.of(
                        "claude", "--model", model, "-p", fullPrompt
                ));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                // Merge stderr into stdout to prevent pipe buffer deadlock
                pb.redirectErrorStream(true);
                // Redirect stdin from /dev/null so claude doesn't block waiting for terminal input
                pb.redirectInput(new File("/dev/null"));

                // Remove CLAUDECODE to avoid nested session errors (same as playground.py)
                Map<String, String> env = pb.environment();
                env.remove("CLAUDECODE");

                System.out.printf("      [AnthropicCLI] spawning claude --model %s (prompt=%dchars, attempt=%d)%n",
                        model, fullPrompt.length(), attempt);
                System.out.flush();
                long t0 = System.nanoTime();
                Process process = pb.start();

                // Read the merged stdout+stderr stream
                StringBuilder stdout = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                }

                boolean finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                long ms = (System.nanoTime() - t0) / 1_000_000;

                if (!finished) {
                    process.destroyForcibly();
                    if (attempt < MAX_RETRIES) {
                        long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                        System.out.printf("      [AnthropicCLI] timeout after %ds, backing off %ds%n",
                                TIMEOUT_SECONDS, backoff / 1000);
                        System.out.flush();
                        try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    throw new LlmException("Claude CLI timed out after " + TIMEOUT_SECONDS + "s", -1, null);
                }

                int exitCode = process.exitValue();
                System.out.printf("      [AnthropicCLI] attempt=%d exit=%d %dms prompt=%dchars%n",
                        attempt, exitCode, ms, fullPrompt.length());
                System.out.flush();

                if (exitCode != 0) {
                    String errMsg = stdout.toString().trim(); // stderr merged into stdout
                    // Rate limit / overload — retry
                    if ((errMsg.contains("rate") || errMsg.contains("overload") || errMsg.contains("529")) && attempt < MAX_RETRIES) {
                        long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                        System.out.printf("      [AnthropicCLI] rate/overload error, backing off %ds%n", backoff / 1000);
                        System.out.flush();
                        try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    throw new LlmException(
                            "Claude CLI exited with code " + exitCode + ": " + errMsg,
                            exitCode, errMsg);
                }

                String result = stdout.toString().trim();
                if (result.isEmpty()) {
                    throw new LlmException("Claude CLI returned empty response", -1, null);
                }
                return result;

            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                    System.out.printf("      [AnthropicCLI] error, backing off %ds: %s%n", backoff / 1000, e.getMessage());
                    System.out.flush();
                    try { Thread.sleep(backoff); } catch (InterruptedException ignored) {}
                    continue;
                }
                throw new LlmException("Claude CLI request failed: " + e.getMessage(), e);
            }
        }
        throw new LlmException("Claude CLI: max retries exhausted", -1, null);
    }

    @Override
    public String provider() {
        return "anthropic-cli";
    }

    @Override
    public String model() {
        return model;
    }
}

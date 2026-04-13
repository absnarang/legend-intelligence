package org.finos.legend.engine.nlq;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.finos.legend.engine.server.LegendHttpServer;
import org.finos.legend.engine.server.LegendHttpJson;
import org.finos.legend.pure.dsl.definition.PureModelBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Extends the base Legend HTTP server with the NLQ endpoint.
 *
 * Run with:
 * GEMINI_API_KEY=... java -cp nlq/target/classes:engine/target/classes \
 * org.finos.legend.engine.nlq.NlqHttpServer [port]
 */
public class NlqHttpServer {

    public static void main(String[] args) throws IOException {
        loadDotEnv();
        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort);
            } catch (NumberFormatException e) {
                System.err.println("Invalid PORT env var: " + envPort);
            }
        }
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
            }
        }

        LegendHttpServer server = new LegendHttpServer(port);
        server.addContext("/engine/nlq", new NlqHandler());
        server.start();

        System.out.println();
        System.out.println("======================================");
        System.out.println("  Legend Studio Lite - Backend Ready");
        System.out.println("  (NLQ enabled)");
        System.out.println("======================================");
        System.out.println();
        System.out.println("Endpoints:");
        System.out.println("  POST http://localhost:" + port + "/lsp             - LSP Protocol");
        System.out.println("  POST http://localhost:" + port + "/engine/execute   - Execute Pure query");
        System.out.println("  POST http://localhost:" + port + "/engine/sql       - Execute raw SQL");
        System.out.println("  POST http://localhost:" + port + "/engine/nlq       - NLQ to Pure query");
        System.out.println("  GET  http://localhost:" + port + "/health           - Health check");
        System.out.println();
        System.out.println("Press Ctrl+C to stop");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }

    /**
     * Loads variables from .env file in the working directory (project root).
     * Values are stored as JVM system properties (prefix: "dotenv.") so that
     * LlmClient implementations can fall back to them when env vars are absent.
     *
     * Prefer using start-nlq.sh which sources .env into the shell environment
     * before launching the JVM — that approach works for all env var consumers.
     */
    private static void loadDotEnv() {
        File envFile = new File(".env");
        if (!envFile.exists()) {
            envFile = new File("../.env");
        }
        if (!envFile.exists()) return;

        Properties loaded = new Properties();
        try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                // Strip optional surrounding quotes
                if (value.length() >= 2 &&
                        ((value.startsWith("\"") && value.endsWith("\"")) ||
                         (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isBlank()) {
                    loaded.setProperty(key, value);
                }
            }
        } catch (Exception e) {
            System.err.println("[NlqHttpServer] Warning: could not read .env: " + e.getMessage());
            return;
        }

        // Store as system properties so clients can read them
        // Env vars (set by start-nlq.sh) take precedence over .env values
        int count = 0;
        for (String key : loaded.stringPropertyNames()) {
            if (System.getenv(key) == null && System.getProperty(key) == null) {
                System.setProperty(key, loaded.getProperty(key));
                count++;
            }
        }
        if (count > 0) {
            System.out.printf("[NlqHttpServer] Loaded %d variable(s) from %s as system properties%n",
                    count, envFile.getPath());
            System.out.println("[NlqHttpServer] Tip: use start-nlq.sh to export .env into the shell for full compatibility.");
        }
    }

    /**
     * NLQ - Natural Language Query to Pure.
     *
     * Request format:
     * {
     * "code": "full Pure model source",
     * "question": "show me total PnL by trader",
     * "domain": "PnL",           // optional hint
     * "model": "claude-haiku-4-5" // optional model override
     * }
     */
    static class NlqHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            LegendHttpServer.addCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                LegendHttpServer.sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }

            try {
                String body = LegendHttpServer.readBody(exchange);
                Map<String, Object> request = LegendHttpJson.parseObject(body);
                String pureSource = LegendHttpJson.getString(request, "code");
                String question = LegendHttpJson.getString(request, "question");
                String domain = LegendHttpJson.getString(request, "domain");

                if (pureSource == null || pureSource.isBlank()) {
                    LegendHttpServer.sendResponse(exchange, 400, "{\"error\":\"Missing 'code' field\"}");
                    return;
                }
                if (question == null || question.isBlank()) {
                    LegendHttpServer.sendResponse(exchange, 400, "{\"error\":\"Missing 'question' field\"}");
                    return;
                }

                // Optional model override from request payload
                String modelOverride = LegendHttpJson.getString(request, "model");

                // Build model and index
                PureModelBuilder modelBuilder = new PureModelBuilder();
                modelBuilder.addSource(pureSource);

                SemanticIndex index = new SemanticIndex();
                index.buildIndex(modelBuilder);

                // Create LLM client (with optional per-request model override)
                LlmClient llmClient = LlmClientFactory.create(modelOverride);

                // Run NLQ pipeline
                NlqService nlqService = new NlqService(index, modelBuilder, llmClient);
                NlqResult result = nlqService.process(question, domain);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", result.isValid());
                response.put("rootClass", result.rootClass());
                response.put("pureQuery", result.pureQuery());
                response.put("explanation", result.explanation());
                response.put("queryPlan", result.queryPlan());
                response.put("retrievedClasses", result.retrievedClasses());
                response.put("latencyMs", result.latencyMs());

                if (result.validationError() != null) {
                    response.put("error", result.validationError());
                }

                LegendHttpServer.sendResponse(exchange, 200, LegendHttpJson.toJson(response));

            } catch (LlmClient.LlmException e) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", false);
                response.put("error", "LLM Error: " + e.getMessage());
                if (e.statusCode() > 0) {
                    response.put("statusCode", e.statusCode());
                }
                LegendHttpServer.sendResponse(exchange, 200, LegendHttpJson.toJson(response));
            } catch (Exception e) {
                e.printStackTrace();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", false);
                response.put("error", e.getMessage());
                LegendHttpServer.sendResponse(exchange, 200, LegendHttpJson.toJson(response));
            }
        }
    }
}

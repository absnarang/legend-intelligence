package org.finos.legend.engine.nlq;

import org.finos.legend.pure.dsl.PureParser;
import org.finos.legend.pure.dsl.definition.PureModelBuilder;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single-call NLQ-to-Pure pipeline.
 *
 * Collapses the old 3-step (Router → Planner → Generator) into ONE LLM call
 * that returns {"rootClass": "...", "pureQuery": "..."}.
 *
 * Retry on parse failure: passes the failed query + error back so the model
 * can self-correct without starting from scratch.
 */
public class NlqService {

    private static final int DEFAULT_TOP_K = 15;
    private static final int MAX_RETRIES    = 2;

    // ── Combined system prompt: routing + generation in one shot ──────────────

    private static final String SYSTEM_PROMPT = """
            Pure query generator. Given a data model and question, return ONLY:
            {"rootClass": "pkg::Class", "pureQuery": "pkg::Class.all()->..."}

            RULES:
            - Fully-qualify: etf::Fund not Fund
            - project() FIRST, then filter/sort/limit/groupBy
            - project: [f|$f.prop, f|$f.assoc.prop], ['alias', 'alias2'] — camelCase aliases, single quotes
            - filter after project: ->filter({row|$row.col == 'X'})  — use $row.prop, no getString()
            - filter before project: ONLY for association: ->filter({h|$h.fund.ticker == 'SPY'})->project(...)
            - sort: ->sort('col') or ->sort(descending('col'))
            - limit: ->limit(5)
            - groupBy: ->groupBy([{r|$r.col}],[{r|$r.val->sum()}],['col','total'])  agg: sum/avg/count/min/max

            EXAMPLES:
            {"rootClass":"etf::Fund","pureQuery":"etf::Fund.all()->project([f|$f.ticker,f|$f.aum],['ticker','aum'])->filter({row|$row.aum>100000})->sort(descending('aum'))->limit(5)"}
            {"rootClass":"etf::Holding","pureQuery":"etf::Holding.all()->filter({h|$h.fund.ticker=='SPY'})->project([h|$h.security.ticker,h|$h.weight],['sec','weight'])->sort(descending('weight'))"}
            {"rootClass":"etf::Holding","pureQuery":"etf::Holding.all()->filter({h|$h.security.ticker=='AAPL'})->project([h|$h.fund.ticker,h|$h.marketValue],['fund','mv'])->groupBy([{r|$r.fund}],[{r|$r.mv->sum()}],['fund','total'])"}
            """;

    private final SemanticIndex index;
    private final PureModelBuilder modelBuilder;
    private final LlmClient llmClient;

    public NlqService(SemanticIndex index, PureModelBuilder modelBuilder, LlmClient llmClient) {
        this.index = index;
        this.modelBuilder = modelBuilder;
        this.llmClient = llmClient;
    }

    /**
     * Runs the single-call NLQ-to-Pure pipeline.
     */
    public NlqResult process(String question, String domain) {
        long start = System.nanoTime();

        try {
            // Retrieve relevant classes via TF-IDF semantic index
            List<SemanticIndex.RetrievalResult> retrieved = index.retrieve(question, DEFAULT_TOP_K, domain);
            Set<String> classNames = retrieved.stream()
                    .map(SemanticIndex.RetrievalResult::qualifiedName)
                    .collect(Collectors.toSet());
            List<String> retrievedList = retrieved.stream()
                    .map(r -> simpleName(r.qualifiedName()))
                    .toList();

            // Extract focused schema for context (rich compact format leverages NlqProfile metadata)
            String schema = ModelSchemaExtractor.extractRichCompactSchema(classNames, modelBuilder);

            // Build user message
            String baseMessage = buildMessage(question, schema, null, null);

            String rootClass = null;
            String pureQuery = null;
            Exception lastParseError = null;

            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                String message = (attempt == 0)
                        ? baseMessage
                        : buildRetryMessage(question, schema, pureQuery, lastParseError);

                long t0 = System.nanoTime();
                String response = llmClient.complete(SYSTEM_PROMPT, message);
                long callMs = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("  [NlqService] attempt=%d llmCall=%dms%n", attempt, callMs);
                System.out.flush();

                // Parse JSON response
                try {
                    rootClass = extractJsonField(response, "rootClass");
                    pureQuery = extractPureQuery(response);
                } catch (Exception e) {
                    System.out.printf("  [NlqService] JSON parse error (attempt %d): %s%n", attempt, e.getMessage());
                    System.out.flush();
                    lastParseError = e;
                    continue;
                }

                // Validate Pure syntax
                try {
                    PureParser.parse(pureQuery);
                    lastParseError = null;
                    break; // success
                } catch (Exception e) {
                    lastParseError = e;
                    System.out.printf("  [NlqService] Pure parse error (attempt %d/%d): %s%n",
                            attempt + 1, MAX_RETRIES + 1, e.getMessage());
                    System.out.flush();
                }
            }

            long elapsed = (System.nanoTime() - start) / 1_000_000;

            if (lastParseError != null) {
                // Return the query anyway with a validation warning (still useful)
                return new NlqResult(
                        rootClass != null ? rootClass : "unknown",
                        null,
                        pureQuery != null ? pureQuery : "",
                        "Query generated but failed Pure validation",
                        false,
                        "Pure validation: " + lastParseError.getMessage(),
                        retrievedList,
                        elapsed
                );
            }

            return new NlqResult(
                    rootClass,
                    null,
                    pureQuery,
                    "Generated Pure query for " + rootClass,
                    true,
                    null,
                    retrievedList,
                    elapsed
            );

        } catch (Exception e) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            return NlqResult.error(e.getMessage(), List.of(), elapsed);
        }
    }

    // ── Message builders ─────────────────────────────────────────────────────

    private static String buildMessage(String question, String schema,
                                       String failedQuery, Exception parseError) {
        StringBuilder sb = new StringBuilder();
        sb.append("Data Model:\n").append(schema);
        sb.append("\nQuestion: ").append(question);
        return sb.toString();
    }

    private static String buildRetryMessage(String question, String schema,
                                             String failedQuery, Exception parseError) {
        StringBuilder sb = new StringBuilder();
        sb.append("Data Model:\n").append(schema);
        sb.append("\nQuestion: ").append(question);
        if (failedQuery != null) {
            sb.append("\n\nPrevious attempt failed Pure validation. Fix the query.\nFailed query:\n").append(failedQuery);
            if (parseError != null) {
                sb.append("\nParse error: ").append(parseError.getMessage());
            }
            sb.append("\n\nReturn corrected JSON: {\"rootClass\": \"...\", \"pureQuery\": \"...\"}");
        }
        return sb.toString();
    }

    // ── JSON field extraction ─────────────────────────────────────────────────

    private static String extractJsonField(String json, String field) {
        String cleaned = stripFences(json);
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(cleaned);
        if (m.find()) {
            return m.group(1);
        }
        throw new LlmClient.LlmException(
                "Could not extract '" + field + "' from response: " + json, -1, json);
    }

    /**
     * Extracts the pureQuery field, which may contain special characters.
     * Pure uses single quotes for strings so double quotes are safe JSON delimiters.
     * Handles both compact and pretty-printed JSON.
     */
    private static String extractPureQuery(String json) {
        String cleaned = stripFences(json);

        // Match "pureQuery": "value" — value may span to end of JSON field
        // Pure queries use single quotes; no unescaped double quotes inside
        Pattern p = Pattern.compile("\"pureQuery\"\\s*:\\s*\"(.*?)\"\\s*[,}]", Pattern.DOTALL);
        Matcher m = p.matcher(cleaned);
        if (m.find()) {
            return m.group(1).trim();
        }

        // Fallback: try without trailing delimiter (end of string)
        Pattern p2 = Pattern.compile("\"pureQuery\"\\s*:\\s*\"(.*?)\"\\s*$", Pattern.DOTALL);
        Matcher m2 = p2.matcher(cleaned);
        if (m2.find()) {
            return m2.group(1).trim();
        }

        throw new LlmClient.LlmException(
                "Could not extract 'pureQuery' from response: " + json, -1, json);
    }

    private static String stripFences(String text) {
        String s = text.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
        }
        return s;
    }

    private static String simpleName(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf("::");
        return idx >= 0 ? qualifiedName.substring(idx + 2) : qualifiedName;
    }
}

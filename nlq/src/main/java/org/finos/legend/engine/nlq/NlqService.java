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
            - project() FIRST, then filter/sort/groupBy/take
            - project: [f|$f.prop, f|$f.assoc.prop], ['alias', 'alias2'] — single quotes
            - filter after project: ->filter({row|$row.col == 'X'})  — use $row.alias, only for columns included in project()
            - filter before project: ->filter(f|$f.prop == 'X')->project(...) — use for class properties, especially when the filter column is NOT projected. Works for both direct props and associations (e.g., f|$f.fundType == 'ETF' or h|$h.fund.ticker == 'SPY')
            - sort ascending: ->sort('col')
            - sort descending: ->sort(~col->descending())
            - limit: ->take(5)
            - groupBy: ->groupBy([{r|$r.col}],[{r|$r.val->sum()}],['col','total'])  agg: sum/avg/count/min/max

            COLUMN ALIAS CONVENTIONS:
            - Use SHORT names for association-navigated columns: 'product' not 'productName', 'country' not 'shipCountry', 'customer' not 'companyName', 'category' not 'categoryName'
            - Keep direct numeric/metric property names as-is: 'unitPrice', 'aum', 'weight', 'freight'
            - GroupBy aggregation aliases: count→'{dim}Count' (e.g. 'orderCount'), sum→'total{Prop}' (e.g. 'totalFreight'), avg→'avg{Prop}' (e.g. 'avgPrice')

            ROOT CLASS SELECTION:
            - For "how many X per Y" or "count X by Y": root = the entity being counted (X)
            - For questions about line items / details with parent info: root = detail class (e.g., OrderDetail), navigate to parent via association
            - For "average/sum X per Y": root = the class that OWNS property X, navigate to Y via association
            - When the question mentions both a parent and its details, prefer starting from the detail class to get one row per detail
            - If a class has a whenToUse annotation, follow it

            COMMON MISTAKES — never do these:
            - WRONG: descending('col')  CORRECT: ~col->descending()
            - WRONG: arithmetic in project like $h.marketValue / $h.shares  CORRECT: project raw columns, then ->extend(~alias:{row|$row.col * N}) for derived columns (unit conversions, simple ratios)
            - WRONG: filter after project using property names  CORRECT: post-project filter must use the projected alias names exactly

            WHEN TO DECLINE:
            If the question CANNOT be answered with the given data model, return:
            {"cannotAnswer": true, "followUpQuestion": "A specific clarifying question"}

            Decline ONLY when:
            - Question asks about data clearly outside the model (wrong domain)
            - Question is too vague to pick a single root class or meaningful projection — e.g. "Show the data", "Show the details", "What do we have?"
            - Question requires a specific value the user didn't provide ("show me that order")
            - Question requires computation Pure cannot express (predict, correlate, statistical functions like Sharpe ratio)

            DO NOT decline when:
            - Question is answerable even if imprecise — make a reasonable interpretation
            - Question mentions a concept that maps to a class/property in the model
            - You can construct any reasonable Pure query

            When in doubt and the question mentions a specific entity or concept, GENERATE A QUERY — false declines are worse than imperfect queries. But if the question is maximally vague (no specific entity, table, or column mentioned), DECLINE and ask what data they want.

            EXAMPLES:
            {"rootClass":"etf::Fund","pureQuery":"etf::Fund.all()->project([f|$f.ticker,f|$f.aum],['ticker','aum'])->filter({row|$row.aum>100000})->sort(~aum->descending())->take(5)"}
            {"rootClass":"etf::Holding","pureQuery":"etf::Holding.all()->filter({h|$h.fund.ticker=='SPY'})->project([h|$h.security.ticker,h|$h.weight],['sec','weight'])->sort(~weight->descending())"}
            {"rootClass":"etf::Holding","pureQuery":"etf::Holding.all()->filter({h|$h.security.ticker=='AAPL'})->project([h|$h.fund.ticker,h|$h.marketValue],['fund','mv'])->groupBy([{r|$r.fund}],[{r|$r.mv->sum()}],['fund','total'])"}
            {"rootClass":"etf::Holding","pureQuery":"etf::Holding.all()->project([h|$h.weight,h|$h.marketValue,h|$h.security.companyName,h|$h.security.sector],['weight','marketValue','company','sector'])"}
            {"rootClass":"etf::Holding","pureQuery":"etf::Holding.all()->filter(h|$h.weight > 5)->project([h|$h.holdingId,h|$h.weight,h|$h.marketValue],['holdingId','weight','marketValue'])->sort(~weight->descending())"}
            {"rootClass":"etf::Fund","pureQuery":"etf::Fund.all()->project([f|$f.ticker,f|$f.fundName,f|$f.expenseRatio],['ticker','fundName','expenseRatio'])"}
            {"rootClass":"etf::Fund","pureQuery":"etf::Fund.all()->project([f|$f.ticker,f|$f.fundName,f|$f.expenseRatio],['ticker','fundName','expenseRatio'])->extend(~expenseRatioBps:{row|$row.expenseRatio*10000})"}
            {"rootClass":"etf::Holding","pureQuery":"etf::Holding.all()->project([h|$h.security.ticker,h|$h.fund.ticker,h|$h.marketValue],['security','fund','marketValue'])->sort(~marketValue->descending())->take(10)"}
            {"rootClass":"northwind::model::OrderDetail","pureQuery":"northwind::model::OrderDetail.all()->project([d|$d.order.orderId,d|$d.order.orderDate,d|$d.product.productName,d|$d.quantity,d|$d.unitPrice],['orderId','orderDate','product','quantity','unitPrice'])->sort('orderId')"}
            {"rootClass":"northwind::model::OrderDetail","pureQuery":"northwind::model::OrderDetail.all()->project([d|$d.quantity,d|$d.unitPrice,d|$d.product.productName,d|$d.product.category.categoryName],['quantity','unitPrice','product','category'])"}
            {"rootClass":"northwind::model::Order","pureQuery":"northwind::model::Order.all()->project([o|$o.shipCountry,o|$o.orderId],['country','orderId'])->groupBy([{r|$r.country}],[{r|$r.orderId->count()}],['country','orderCount'])->sort(~orderCount->descending())"}
            {"rootClass":"northwind::model::Product","pureQuery":"northwind::model::Product.all()->project([p|$p.category.categoryName,p|$p.unitPrice],['category','unitPrice'])->groupBy([{r|$r.category}],[{r|$r.unitPrice->avg()}],['category','avgPrice'])->sort('category')"}
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

            // Report original TF-IDF retrieval for precision scoring
            List<String> retrievedList = classNames.stream()
                    .map(NlqService::simpleName)
                    .toList();

            // Expand with 1-hop associations for schema context (gives LLM
            // visibility into neighbor classes for join navigation), but only
            // when TF-IDF returned few classes
            Set<String> schemaClasses = classNames;
            if (classNames.size() <= 3) {
                schemaClasses = index.expandWithAssociations(classNames, modelBuilder, 1);
            }

            // Extract focused schema for context (rich compact format leverages NlqProfile metadata)
            String schema = ModelSchemaExtractor.extractRichCompactSchema(schemaClasses, modelBuilder);

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

                // Check for decline response before normal parsing
                if (extractBooleanField(response, "cannotAnswer")) {
                    String followUp = null;
                    try {
                        followUp = extractJsonField(response, "followUpQuestion");
                    } catch (Exception ignored) {
                        followUp = "Could you provide more details?";
                    }
                    long elapsed = (System.nanoTime() - start) / 1_000_000;
                    return NlqResult.decline(followUp, retrievedList, elapsed);
                }

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
                        elapsed,
                        false,
                        null
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
                    elapsed,
                    false,
                    null
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

    private static boolean extractBooleanField(String json, String field) {
        String cleaned = stripFences(json);
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)");
        Matcher m = p.matcher(cleaned);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return false;
    }

    private static String simpleName(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf("::");
        return idx >= 0 ? qualifiedName.substring(idx + 2) : qualifiedName;
    }
}

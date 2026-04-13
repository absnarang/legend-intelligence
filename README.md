# Legend Intelligence

> **The reasoning brain: deep dive into the engine powering financial AI**

A clean-room implementation of the FINOS Legend Engine paired with a Natural Language Query (NLQ) pipeline — enabling 100% SQL push-down execution of Pure language queries and LLM-powered translation from English questions to Pure.

---

## What It Does

Write strongly-typed Pure language queries (or just ask in plain English), and this engine compiles them 100% to SQL — no data ever leaves the database into the JVM. Backed by DuckDB or SQLite in-memory for instant, zero-infra execution.

```
English question
  → TF-IDF semantic retrieval (SemanticIndex)
  → Rich compact schema extraction (NlqProfile metadata)
  → Single LLM call → {"rootClass": "...", "pureQuery": "..."}
  → Pure syntax validation (self-correcting retry)
  → Execute against DuckDB
```

---

## Modules

| Module | Description |
|--------|-------------|
| `engine/` | Pure parser (ANTLR4), compiler, SQL generator, DuckDB/SQLite execution, HTTP server, LSP server |
| `nlq/` | NLQ pipeline — semantic index (TF-IDF), schema extractor, LLM clients (Anthropic CLI/API, Gemini), single-call pipeline |
| `pct/` | Pure Compatibility Tests against the FINOS Legend specification |

---

## Supported Pure Operations

`filter` · `project` · `groupBy` · `sort` · `limit` · `extend` · `join` · `asOfJoin` · window functions (`rank`, `lag`, `lead`) · `distinct` · TDS literals · 95+ expression types

---

## HTTP Endpoints

Server runs on `http://localhost:8080` by default.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/engine/execute` | Compile + run a Pure query against DuckDB |
| `POST` | `/engine/plan` | Compile Pure → SQL only (no execution) |
| `POST` | `/engine/sql` | Execute raw SQL |
| `POST` | `/engine/nlq` | English → Pure query via LLM pipeline |
| `POST` | `/lsp` | LSP protocol (diagnostics, completions, hover) |
| `GET` | `/health` | Health check |

---

## NLQ Request Format

```json
{
  "code": "<full Pure model source>",
  "question": "show me the top 5 ETFs by AUM",
  "domain": "ETF",
  "model": "claude-haiku-4-5"
}
```

**LLM providers** (set `LLM_PROVIDER` in `.env`):

| Value | Description |
|-------|-------------|
| `anthropic-cli` | `claude -p ...` subprocess — Claude Pro/Max subscription, no API key |
| `anthropic-api` | Anthropic REST API — requires `ANTHROPIC_API_KEY` |
| `gemini` | Google Gemini API — requires `GEMINI_API_KEY` |

---

## Quick Start

**Requirements:** Java 21, Maven 3.8+

```bash
# Build (skip tests for speed)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn install -DskipTests

# Configure LLM provider
cp .env.example .env   # then edit LLM_PROVIDER / API keys

# Start server
./start-nlq.sh > /tmp/legend-server.log 2>&1 &

# Health check
curl http://localhost:8080/health
# → {"status":"ok"}

# Run an NLQ query
curl -X POST http://localhost:8080/engine/nlq \
  -H 'Content-Type: application/json' \
  -d '{"code":"...model...", "question":"top 5 ETFs by AUM", "domain":"ETF"}'
```

---

## NLQ Annotations (NlqProfile)

Decorate your Pure classes with rich metadata that the semantic retrieval and schema extraction layers use to generate accurate queries on the first try:

```pure
Class <<nlq::NlqProfile.core>>
      {nlq::NlqProfile.description = 'An investment fund (ETF or mutual fund)',
       nlq::NlqProfile.synonyms = 'fund, etf, mutual fund, ticker',
       nlq::NlqProfile.sampleValues = 'ticker: SPY, QQQ, VTI',
       nlq::NlqProfile.unit = 'aum: USD millions',
       nlq::NlqProfile.exampleQuestions = 'Top 5 ETFs by AUM?'} etf::Fund
{
  ticker: String[1];
  aum: Float[1];
  assetClass: String[1];
}
```

---

## Build

| Dependency | Version |
|------------|---------|
| DuckDB | 1.4.4 |
| SQLite | 3.47.1 |
| ANTLR4 | 4.13.1 |
| JUnit 5 | 5.x |

Packaged as a single shaded fat JAR — no external server required.

---

## Testing

```bash
# Engine unit tests (~900 tests)
mvn test -pl engine

# Pure Compatibility Tests
mvn test -pl pct

# NLQ evaluation suite
mvn test -pl nlq -Dtest="NlqFullPipelineEvalTest" -DGEMINI_API_KEY=...
```

---

## Related

- [legend-groundzero](https://github.com/absnarang/legend-groundzero) — Streamlit playground UI and NLQ demo

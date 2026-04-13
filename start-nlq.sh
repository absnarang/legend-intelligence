#!/usr/bin/env bash
# Start the Legend Lite NLQ server.
# Loads environment variables from .env in this directory (project-local secrets).
# Usage: ./start-nlq.sh [port]

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load .env if present (skip comments and blank lines)
ENV_FILE="$SCRIPT_DIR/.env"
if [[ -f "$ENV_FILE" ]]; then
    echo "[start-nlq] Loading $ENV_FILE"
    while IFS='=' read -r key value; do
        # Skip comments and blank lines
        [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]] && continue
        key="${key// /}"
        # Strip surrounding quotes from value
        value="${value%\"}"
        value="${value#\"}"
        value="${value%\'}"
        value="${value#\'}"
        # Only export if not already set
        if [[ -z "${!key+x}" ]]; then
            export "$key=$value"
            echo "[start-nlq]   $key set"
        fi
    done < "$ENV_FILE"
fi

# Resolve JAVA_HOME
if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -d /opt/homebrew/opt/openjdk@21 ]]; then
        export JAVA_HOME=/opt/homebrew/opt/openjdk@21
    fi
fi

ENGINE_JAR="$SCRIPT_DIR/engine/target/legend-lite-engine-1.0.0-SNAPSHOT-shaded.jar"
NLQ_JAR="$SCRIPT_DIR/nlq/target/legend-lite-nlq-1.0.0-SNAPSHOT-shaded.jar"

if [[ ! -f "$ENGINE_JAR" ]]; then
    echo "ERROR: Engine JAR not found at $ENGINE_JAR"
    echo "Run: JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn install -DskipTests"
    exit 1
fi
if [[ ! -f "$NLQ_JAR" ]]; then
    echo "ERROR: NLQ JAR not found at $NLQ_JAR"
    echo "Run: JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn install -DskipTests"
    exit 1
fi

echo "[start-nlq] Provider: ${LLM_PROVIDER:-anthropic-cli}"
echo "[start-nlq] Starting NLQ server..."
# Use engine shaded JAR (has working DuckDB) + NLQ thin JAR on classpath.
# NLQ classes take priority (listed first), engine shaded provides DuckDB + all engine deps.
exec "$JAVA_HOME/bin/java" \
    -cp "$NLQ_JAR:$ENGINE_JAR" \
    org.finos.legend.engine.nlq.NlqHttpServer "$@"

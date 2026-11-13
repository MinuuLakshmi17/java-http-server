#!/usr/bin/env bash
# Starts the server on port 8080, runs the benchmark against it, then stops it.
# Usage: ./benchmark.sh [requests] [threads]
set -euo pipefail
cd "$(dirname "$0")"

if command -v java >/dev/null 2>&1; then
  JAVA=java
elif [ -x "$HOME/workspace/tools/jdk-17/bin/java" ]; then
  JAVA="$HOME/workspace/tools/jdk-17/bin/java"
else
  echo "error: no JDK found. Install JDK 17+ and put java on PATH." >&2
  exit 1
fi

REQUESTS="${1:-10000}"
THREADS="${2:-16}"

./build.sh >/dev/null
"$JAVA" -cp out com.minuu.httpserver.Main --port 8080 >/tmp/java-http-server.log 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null; wait $SERVER_PID 2>/dev/null' EXIT

# Wait for the health endpoint to answer.
for _ in $(seq 1 50); do
  if curl -fsS -o /dev/null http://localhost:8080/api/health 2>/dev/null; then
    break
  fi
  sleep 0.2
done

"$JAVA" -cp out com.minuu.httpserver.tools.Benchmark 127.0.0.1 8080 "$REQUESTS" "$THREADS"

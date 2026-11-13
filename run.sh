#!/usr/bin/env bash
# Builds and starts the server. Default port 8080; pass --port N to change it.
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

./build.sh >/dev/null
exec "$JAVA" -cp out com.minuu.httpserver.Main "$@"

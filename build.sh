#!/usr/bin/env bash
# Compiles the main sources. Needs only a JDK 17+.
set -euo pipefail
cd "$(dirname "$0")"

if command -v javac >/dev/null 2>&1; then
  JAVAC=javac
  JAVA=java
elif [ -x "$HOME/workspace/tools/jdk-17/bin/javac" ]; then
  JAVAC="$HOME/workspace/tools/jdk-17/bin/javac"
  JAVA="$HOME/workspace/tools/jdk-17/bin/java"
else
  echo "error: no JDK found. Install JDK 17+ and put javac on PATH." >&2
  exit 1
fi

rm -rf out
mkdir -p out
# shellcheck disable=SC2046
"$JAVAC" -encoding UTF-8 -d out $(find src/main/java -name "*.java")
echo "Build OK: $(find out -name '*.class' | wc -l | tr -d ' ') classes in out/"

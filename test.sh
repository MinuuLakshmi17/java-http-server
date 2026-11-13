#!/usr/bin/env bash
# Compiles everything and runs the full test suite (unit + live socket tests).
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

rm -rf out out-test
mkdir -p out out-test
# shellcheck disable=SC2046
"$JAVAC" -encoding UTF-8 -d out $(find src/main/java -name "*.java")
# shellcheck disable=SC2046
"$JAVAC" -encoding UTF-8 -cp out -d out-test $(find src/test/java -name "*.java")
"$JAVA" -cp out:out-test com.minuu.httpserver.TestRunner

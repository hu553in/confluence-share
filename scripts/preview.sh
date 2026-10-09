#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q test-compile dependency:build-classpath -Dmdep.outputFile=target/preview-classpath.txt -DincludeScope=test
java_command=java
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_command="$JAVA_HOME/bin/java"
fi
exec "$java_command" -cp "target/test-classes:target/classes:$(cat target/preview-classpath.txt)" dev.confluenceshare.DevPreview "${1:-8098}"

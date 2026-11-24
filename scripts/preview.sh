#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_command=java
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_command="$JAVA_HOME/bin/java"
fi
classpath=$(cat target/preview-classpath.txt)
exec "$java_command" -cp "target/test-classes:target/classes:$classpath" dev.confluenceshare.DevPreview "${1:-8098}"

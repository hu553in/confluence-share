#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=target/preview-classpath.txt -DincludeScope=test
exec java -cp "target/test-classes:target/classes:$(cat target/preview-classpath.txt)" dev.confluenceshare.DevPreview "${1:-8098}"

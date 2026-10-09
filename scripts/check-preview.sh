#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p target
# Give the build and preview their own process group for cleanup on startup failure.
set -m
bash scripts/preview.sh 0 >target/preview.log 2>&1 &
preview_pid=$!
set +m
trap 'kill -- "-$preview_pid" 2>/dev/null || true; wait "$preview_pid" 2>/dev/null || true' EXIT

for ((attempt = 0; attempt < 60; attempt++)); do
  if ! kill -0 "$preview_pid" 2>/dev/null; then
    cat target/preview.log >&2
    exit 1
  fi
  url=$(sed -n 's/^Local UI demo: //p' target/preview.log)
  if [[ -n "$url" ]]; then
    curl --fail --silent --show-error --max-time 10 "$url" >target/preview.html
    grep -Fq '<h1>Public link</h1>' target/preview.html
    grep -Fq 'name="csrf"' target/preview.html
    exit 0
  fi
  sleep 1
done

cat target/preview.log >&2
echo 'Preview did not become ready within 60 seconds.' >&2
exit 1

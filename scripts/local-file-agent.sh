#!/usr/bin/env bash
set -euo pipefail

WATCH_DIR="${DATASIFTER_WATCH_DIR:-./data/inbox}"
ENDPOINT="${DATASIFTER_ENDPOINT:-http://localhost:8080}"
WORKFLOW_ID="${DATASIFTER_WORKFLOW_ID:?Set DATASIFTER_WORKFLOW_ID to the workflow receiving these CSV files}"
USERNAME="${DATASIFTER_USERNAME:?Set DATASIFTER_USERNAME for an operator account}"
PASSWORD="${DATASIFTER_PASSWORD:?Set DATASIFTER_PASSWORD for an operator account}"
POLL_INTERVAL="${DATASIFTER_POLL_INTERVAL:-10}"
COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

mkdir -p "$WATCH_DIR"
echo "DataSifter CSV file agent started"
echo "Watching $WATCH_DIR"

while true; do
  shopt -s nullglob
  files=("$WATCH_DIR"/*.csv)
  shopt -u nullglob

  for file in "${files[@]}"; do
    csrf="$(curl -fsS -u "$USERNAME:$PASSWORD" -c "$COOKIE_JAR" -b "$COOKIE_JAR" "$ENDPOINT/api/csrf" \
      | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
    if [[ -z "$csrf" ]]; then
      echo "Could not obtain a CSRF token; leaving $file in place" >&2
      exit 1
    fi
    curl -fsS -u "$USERNAME:$PASSWORD" -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
      -H "X-XSRF-TOKEN: $csrf" \
      -F "file=@$file;type=text/csv" \
      "$ENDPOINT/api/workflows/$WORKFLOW_ID/csv" >/dev/null

    filename="$(basename "$file")"
    mv "$file" "$file.processed"
    echo "Uploaded $filename to workflow $WORKFLOW_ID"
  done

  sleep "$POLL_INTERVAL"
done

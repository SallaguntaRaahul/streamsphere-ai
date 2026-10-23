#!/usr/bin/env bash
# Waits until every compose service with a healthcheck reports healthy (or fails after a timeout).
set -euo pipefail
deadline=$(( $(date +%s) + ${1:-300} ))
while true; do
  status=$(docker compose ps --format '{{.Service}}|{{.Health}}|{{.State}}')
  bad=$(echo "$status" | awk -F'|' '$2!="" && $2!="healthy" {print $1"("$2")"}' | tr '\n' ' ')
  if [ -z "$bad" ]; then echo "all services healthy"; exit 0; fi
  if [ "$(date +%s)" -gt "$deadline" ]; then echo "not healthy: $bad"; exit 1; fi
  sleep 5
done

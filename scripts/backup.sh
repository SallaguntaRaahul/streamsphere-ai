#!/usr/bin/env bash
# Logical backup of every service database (pg_dump custom format) into ./backups/<timestamp>/.
# No free, approved off-node destination exists yet (FREE_TIER_VERIFICATION.md) → copies stay local.
set -euo pipefail
cd "$(dirname "$0")/.."
TS=$(date -u +%Y%m%dT%H%M%SZ); OUT=backups/$TS; mkdir -p "$OUT"
for db in profile catalog creator upload media playback history search recommendations moderation analytics notifications keycloak; do
  docker compose exec -T postgres pg_dump -U postgres -Fc "$db" > "$OUT/$db.dump"
done
( cd "$OUT" && shasum -a 256 *.dump > SHA256SUMS )
echo "$OUT"

#!/usr/bin/env bash
# Prints an access token for a seeded LOCAL test user via the local-only e2e client.
# Usage: scripts/token.sh <viewer|creator|creator2|moderator|admin>
set -euo pipefail
user="${1:?user}"
KC="${KEYCLOAK_URL:-http://localhost:8180}"
pass=$(python3 -c "import json,sys;r=json.load(open('$(dirname "$0")/../infrastructure/docker/keycloak/streamsphere-realm.json'));print(next(c['value'] for u in r['users'] if u['username']=='$user' for c in u.get('credentials',[])))")
curl -sf -d grant_type=password -d client_id=streamsphere-e2e -d username="$user" --data-urlencode password="$pass" \
  "$KC/realms/streamsphere/protocol/openid-connect/token" | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])'

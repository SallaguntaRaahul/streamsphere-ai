#!/usr/bin/env bash
# Replays dead-lettered events for one service back onto its main queue (after the cause is fixed).
# Consumers are idempotent (inbox dedup), so replaying an already-processed event is harmless.
# Usage: scripts/dlq-replay.sh <service> [max]
set -euo pipefail
cd "$(dirname "$0")/.."
SVC=${1:?service}; MAX=${2:-100}
set -a; . ./.env; set +a
API=http://localhost:15672/api
auth=(-u "$RABBITMQ_USER:$RABBITMQ_PASSWORD")
n=0
while [ "$n" -lt "$MAX" ]; do
  msg=$(curl -sf "${auth[@]}" -H 'content-type: application/json' -X POST "$API/queues/%2F/$SVC.dlq/get" \
        -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"base64"}')
  [ "$msg" = "[]" ] && break
  payload=$(echo "$msg" | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["payload"])')
  curl -sf "${auth[@]}" -H 'content-type: application/json' -X POST "$API/exchanges/%2F/amq.default/publish" \
       -d "{\"routing_key\":\"$SVC.events\",\"payload\":\"$payload\",\"payload_encoding\":\"base64\",\"properties\":{\"delivery_mode\":2}}" >/dev/null
  n=$((n+1))
done
echo "replayed $n message(s) from $SVC.dlq"

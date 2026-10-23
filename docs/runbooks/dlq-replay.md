# Runbook: Events dead-lettered

**Trigger:** Alert `EventsDeadLettered`.

1. Find cause: `docker compose logs <svc> | grep dead-letter` (poison = unparseable; exhausted = handler kept failing).
2. Fix the cause (bug, missing dependency).
3. `scripts/dlq-replay.sh <svc> [max]` — safe: consumers dedupe by eventId.
4. Poison messages will dead-letter again; inspect in RabbitMQ UI (:15672) and discard deliberately.

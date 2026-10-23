# Runbook: RabbitMQ down / outbox relay failing

**Trigger:** Alert `OutboxRelayFailing`.

Business writes keep succeeding (events wait in each service's `outbox_events`). 1. `docker compose ps rabbitmq`, check disk/memory alarms. 2. Restart: `docker compose restart rabbitmq`. 3. Verify relays drain: `select count(*) from outbox_events where published_at is null` per DB → 0. Verified by drill (pipeline completed after restart).

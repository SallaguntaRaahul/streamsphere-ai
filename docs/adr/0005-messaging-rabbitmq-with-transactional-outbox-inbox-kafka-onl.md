# ADR-0005: Messaging: RabbitMQ with transactional outbox/inbox; Kafka only in local-full

- Status: Accepted · Date: 2026-10-22

## Context
Durable async workflows (upload → transcode → review → publish → index) with at-least-once delivery.

## Options
(a) RabbitMQ only; (b) Kafka only; (c) both in production.

## Decision
RabbitMQ 4 topic exchange `streamsphere.events` (routing key `<EventType>.v<N>`). Producers write events into their own `outbox_events` table in the business transaction; a relay publishes with publisher confirms (`FOR UPDATE SKIP LOCKED`). Consumers use manual acks and `inbox_deduplication(consumer,event_id)`; failures are re-queued through a per-service retry queue with per-message TTL (exponential + jitter, max 5) then dead-lettered to `<svc>.dlq`. Replay = `scripts/dlq-replay.sh`. Kafka (`apache/kafka:4.1.2`, KRaft) runs in `local-full`; analytics can consume playback events from a Kafka topic to demonstrate offsets/consumer groups/replay. Exactly-once business semantics are **not** claimed.

## Rationale
RabbitMQ is light (~150 MB) and fits oci-free; Kafka would not fit alongside everything else.

## Consequences
Two code paths for analytics ingestion.

## Revisit Trigger
Analytics volume needs log retention/replay in production.

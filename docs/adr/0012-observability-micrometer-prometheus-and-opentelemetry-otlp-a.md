# ADR-0012: Observability: Micrometer/Prometheus + OpenTelemetry OTLP + structured JSON logs

- Status: Accepted · Date: 2026-10-22

## Context
Metrics, traces, logs with low cardinality.

## Options
Vendor APM vs OSS stack.

## Decision
Every service exposes `/actuator/prometheus`; traces via `spring-boot-starter-opentelemetry` to an OTel Collector → Tempo (local-full); JSON logs (ECS format) with `traceId` and `correlationId`; Grafana dashboards + Prometheus alert rules in `observability/`. Labels never include user/title IDs.

## Rationale
Free, standard.

## Consequences
Full stack is local-full only; oci-free runs Prometheus with 3-day retention.

## Revisit Trigger
—

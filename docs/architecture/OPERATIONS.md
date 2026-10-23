# Operations

- Start / stop: `make up`, `make down`; health: `./scripts/wait-healthy.sh`, `GET /api/v1/admin/health` (admin).
- Logs: structured ECS JSON with `traceId` and `correlationId` (`docker compose logs <svc>`); every problem response returns the correlation id.
- Metrics: `/actuator/prometheus` on every service; dashboard `observability/dashboards/platform-overview.json`; alerts `observability/alerts/streamsphere.yml`.
- Traces: `TRACING_EXPORT=true make up-full` → OTel Collector → Tempo (Grafana :3000).
- Runbooks: `docs/runbooks/` (DLQ replay, broker outage, cost guard, transcode backlog, high error rate, playback latency, backup/restore).
- Cost: admin dashboard (`/admin`) shows estimated storage ratio and operation counts; uploads stop at 95 %.

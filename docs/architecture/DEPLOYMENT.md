# Deployment profiles

| Profile | Status (2026-10-22) | How |
|---|---|---|
| **local-core** | ✅ running & verified | `make up` (Docker Compose, ~3.9 GB RAM) |
| **local-full** | ✅ verified: tusd resumable uploads (9/9 e2e), Kafka replayable analytics log (4/4), Prometheus 14/14 targets, 8 alert rules, Grafana dashboard, cross-service traces in Tempo, OTLP logs from 11 services in Loki | `docker compose --profile full up -d` with `TRACING_EXPORT=true ANALYTICS_KAFKA_ENABLED=true` |
| **any Docker host** | ✅ deploy-ready: images configurable via `IMAGE_PREFIX`/`IMAGE_TAG` (CI publishes to GHCR), production web image (nginx, runtime config) | [docs/runbooks/deploy.md](../runbooks/deploy.md) | `make up-full` (+OTel, Tempo, Loki, Prometheus, Grafana, Kafka, Mailpit) |
| **local k8s** | ✅ k3s v1.36 in Docker; FFmpeg **Kubernetes Job** transcode verified (asset READY via `k8s-job` launcher) | `scripts/k8s-local-up.sh`, `compose.k8s.yaml`; Helm chart linted + templated for all 13 services |
| **oci-free** | ⛔ BLOCKED — needs owner confirmations + credentials | OpenTofu plan-only module (validated), Helm values, k3s cloud-init |
| **paid-future** | ⛔ not applicable without approval | managed K8s/DB/video — not implemented |

## oci-free design (not deployed)
One Ampere A1 VM (2 OCPU/12 GB) with k3s; all services as separate Deployments from the shared chart; media
uses `TRANSCODE_LAUNCHER=k8s-job` (RBAC limited to Jobs in its namespace); R2 replaces MinIO via the same S3
provider config; SPA on Cloudflare Pages. **Limitations:** no purchased domain → the API needs a hostname with
a trusted certificate (a free DNS name or Cloudflare Tunnel must be verified first); single node = no HA;
Keycloak + 13 JVMs must fit the budget in CAPACITY_PLAN.md.

## Rollback & migrations
Helm `rollout undo`/previous image digest; Flyway migrations are **expand-then-contract** (add columns/tables
first, deploy code, drop later) so N-1 code runs against N schema; never edit an applied migration.

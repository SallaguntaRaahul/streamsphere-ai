# Capacity plan (oci-free: 2 OCPU / 12 GB, planning ceiling)

Measured locally (docker stats, idle after warm-up, 2026-10-22): Spring services 160–240 MiB RSS each,
Keycloak ~490 MiB, PostgreSQL ~210 MiB, RabbitMQ ~130 MiB, MinIO ~100 MiB, Valkey ~14 MiB, recommendations
~180 MiB (+ONNX model on first embed), FFmpeg worker idle ~120 MiB, ~1.2 GB peak while encoding 720p.

| Component | Request | Limit | Count | Limit total |
|---|---|---|---|---|
| Java services | 256 Mi | 384 Mi | 12 | 4.5 GiB |
| gateway | 256 Mi | 320 Mi | 1 | 0.3 GiB |
| recommendations | 256 Mi | 512 Mi | 1 | 0.5 GiB |
| Keycloak | 512 Mi | 768 Mi | 1 | 0.75 GiB |
| PostgreSQL | 384 Mi | 512 Mi | 1 | 0.5 GiB |
| RabbitMQ + Valkey | — | 480 Mi | 2 | 0.47 GiB |
| FFmpeg Job (concurrency 1) | 512 Mi | 1.5 GiB | 1 | 1.5 GiB |
| Prometheus (3-day retention) | — | 384 Mi | 1 | 0.38 GiB |
| k3s + OS overhead | | | | ~1.2 GiB |
| **Total** | | | | **≈10.1 GiB of 12 GiB** (~16 % headroom) |

CPU: 13 services at 50m requests; bursts share 2 OCPUs; FFmpeg Job limited to 1.5 CPU → encode throughput
≈ 1 job at a time (20-s 720p source took 10–15 s wall locally on Apple Silicon; ARM A1 is slower — unmeasured).
Storage: R2 10 GB free tier → ≈4 hours of 3-rung content (COST_MODEL.md). Kafka, Loki, Tempo, Grafana are **not**
in the oci-free budget.

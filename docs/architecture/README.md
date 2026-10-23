# Architecture overview

StreamSphere AI is a creator-first VOD platform built as **strictly separated microservices**: every business
service owns its database, migrations, API, metrics and container, and talks to others only through
versioned REST APIs or events. This folder describes the system **as implemented and verified on 2026-10-22**.

| Doc | Purpose |
|---|---|
| [C4_CONTEXT](C4_CONTEXT.md) · [C4_CONTAINER](C4_CONTAINER.md) · [C4_COMPONENTS](C4_COMPONENTS.md) | Structure |
| [MEDIA_PIPELINE](MEDIA_PIPELINE.md) | Upload → transcode → secure HLS, states, failure handling |
| [DATA_OWNERSHIP](DATA_OWNERSHIP.md) · [EVENT_CATALOG](EVENT_CATALOG.md) · [API_CATALOG](API_CATALOG.md) | Contracts |
| [THREAT_MODEL](THREAT_MODEL.md) | Security analysis and controls |
| [DEPLOYMENT](DEPLOYMENT.md) · [CAPACITY_PLAN](CAPACITY_PLAN.md) · [RELIABILITY](RELIABILITY.md) · [OPERATIONS](OPERATIONS.md) | Running it |
| [DECISION_LOG](DECISION_LOG.md) | Index of ADRs + notable decisions discovered while verifying |

## Services

| Service | Tech | Owns database | Responsibility |
|---|---|---|---|
| gateway | Spring Cloud Gateway (MVC) | — | Routing, JWT pre-validation, CORS, rate limit, security headers, blocks /internal, admin health |
| profile | Spring Boot | profile | Viewer profile & accessibility prefs |
| catalog | Spring Boot | catalog | Titles, seasons/episodes, editorial lifecycle, public browse (Valkey cache-aside) |
| creator | Spring Boot | creator | Creator registration, append-only rights attestations |
| upload | Spring Boot | upload | Multipart presign, server verification, cost guard, idempotency, reconciler |
| media | Spring Boot | media | Assets, jobs, renditions, captions, transcode launcher (queue / k8s Job), reconciler, posters |
| ffmpeg-worker | Java + FFmpeg | — (object storage only) | Probe, ABR HLS encode, validate, upload immutable output |
| playback | Spring Boot | playback | Session authorization, HMAC tokens, playlists, presigned segment redirects, revocation |
| history | Spring Boot | history | Watch progress, continue watching, watchlist |
| search | Spring Boot | search (+pgvector, pg_trgm) | Hybrid FTS + vector search (RRF), suggestions |
| recommendations | FastAPI (Python) | recommendations (+pgvector) | Embeddings (bge-small, ONNX), personalized + cold-start recs, eval |
| moderation | Spring Boot | moderation | Review queue, decisions, reports, takedowns, appeals, audit trail |
| analytics | Spring Boot | analytics | Playback events, QoE beacons, creator daily stats (k-anonymity) |
| notifications | Spring Boot | notifications | In-app notifications, delivery attempts (email disabled) |

Shared code lives only in `packages/technical-common` (problem+json, correlation ids, JWT validation,
outbox/inbox, messaging, S3 client, safe cache) — **no domain models are shared**.

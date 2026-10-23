# C4 — Containers (local-core, as run by `docker compose`)

```mermaid
flowchart LR
  subgraph Browser
    SPA[Angular 22 SPA<br/>hls.js / native HLS]
  end
  SPA -->|REST + JWT| GW[gateway]
  SPA -->|PUT parts / GET segments<br/>presigned| S3[(MinIO / R2)]
  SPA -->|OIDC PKCE| KC[Keycloak]
  GW --> CAT[catalog] & CRE[creator] & PRO[profile] & UPL[upload] & MED[media] & PLB[playback]
  GW --> HIS[history] & SEA[search] & REC[recommendations] & MOD[moderation] & ANA[analytics] & NOT[notifications]
  CAT -. user token .-> CRE
  UPL -. user token .-> CAT & CRE
  PLB -. service token .-> CAT & MED
  MOD -. service token .-> CRE
  SEA -. service token .-> REC
  UPL & MED & PLB --> S3
  W[ffmpeg-worker<br/>queue consumer or k8s Job] --> S3
  subgraph Data
    PG[(PostgreSQL 16 + pgvector<br/>one DB + role per service)]
    MQ[[RabbitMQ topic exchange<br/>streamsphere.events + DLX]]
    VK[(Valkey)]
  end
  CAT & CRE & PRO & UPL & MED & PLB & HIS & SEA & REC & MOD & ANA & NOT --> PG
  CAT & UPL & MED & PLB & HIS & MOD -->|outbox relay| MQ
  MQ --> CAT & UPL & MED & PLB & HIS & SEA & REC & MOD & ANA & NOT & W
  W -->|results| MQ
  CAT & PLB --> VK
```
Dotted lines are synchronous API calls (never database access). Optional local-full containers: OTel
Collector, Tempo, Loki, Prometheus, Grafana, Kafka, Mailpit.

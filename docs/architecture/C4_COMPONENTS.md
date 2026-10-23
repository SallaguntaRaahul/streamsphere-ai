# C4 — Components (key services)

**technical-common (auto-configured into every service)**
- `SecurityAutoConfiguration` — RS256 via JWKS, issuer + audience (`streamsphere-api`) validators, realm roles → authorities, stateless, CSP.
- `OutboxWriter` (MANDATORY tx) → `outbox_events`; `OutboxRelay` (FOR UPDATE SKIP LOCKED, publisher confirms).
- `EventConsumer` — manual ack; inbox dedup + handler in one tx; retry queue with per-message TTL (exp. backoff, full jitter, cap 60 s, 5 attempts) → DLQ; poison → DLQ.
- `ProblemDetailsAdvice` (+ data/security/storage advices) — RFC 9457 problem+json with correlation id.
- `S3StorageProvider`, `SafeCache` (Valkey cache-aside that degrades to the database), `ServiceTokenClient` (client credentials).

**upload**: `UploadService` (create/presign/complete/cancel), `MediaSignature` (container sniffing), `CostGuard`, `OwnershipClient`, `UploadMaintenance` (expiry reconciler, source retention, media-bytes projection).

**media**: `MediaPipeline` (state machine + reconciler), `Launchers` (`queue` | `k8s-job` via fabric8), `MediaController` (asset detail, captions, posters, internal manifest).

**ffmpeg-worker**: `Transcoder` (download → sha256 → probe → encode → poster/thumbs → validate → upload → `_COMPLETE`), `Probe`, `Ladder`, `HlsValidator`, `Proc` (argv-only, timeouts).

**playback**: `PlaybackController` (session policy, playlist generation, segment redirect), `PlaybackToken` (HMAC), `Revocations` (Valkey with loaded-marker + DB fallback + 30-s resync), `Upstreams`.

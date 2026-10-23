# Decision log

ADRs: `docs/adr/0001`–`0016`. Decisions taken during verification:

| Date | Decision | Why |
|---|---|---|
| 2026-10-22 | Boot 4.0.9 + Cloud 2025.1.4 instead of 4.1.2 | Cloud train for 4.1 only RC (ADR-0002) |
| 2026-10-22 | MinIO from cached digest, local only | upstream archived (ADR-0007) |
| 2026-10-22 | Angular 22 built in `node:22` container | Angular 22 needs Node ≥22.22; host has 20.20; owner chose containers over host installs |
| 2026-10-22 | Storage CORS `*` | Origin becomes `null` after cross-origin 302 (found in browser test; ADR-0008) |
| 2026-10-22 | Valkey host port 16379, Kafka 19092 | other local projects hold 6379/9092 |
| 2026-10-22 | Storage outages → 503 `storage-unavailable`; JVM negative DNS TTL 1 s | found by object-store drill |
| 2026-10-22 | Project moved to `~/Developer/streamsphere-ai` | iCloud evicted files on a 99 %-full disk; owner approved |

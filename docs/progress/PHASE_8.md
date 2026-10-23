# Phase 8: Optional integrations

**Acceptance status:** ✅

**Implemented:** Kafka replayable analytics log (3 partitions, replay with fresh consumer group, idempotent rebuild of the covered window), tusd adapter, paid adapters (MediaConvert/Mux/Cloudflare Stream) refusing to start without two flags + unit tests

**Evidence:** docs/benchmarks/2026-10-22-local.md

**Blockers / remaining:** Paid adapters are guarded stubs by design (no spend); real API integration needs owner budget

# Product requirements (MVP)

**Vision:** a creator-first streaming platform for independent, original and openly licensed films — with
transparent analytics, accessible viewing and explainable discovery. No scraped or unlicensed catalogs; no
payments in MVP.

**Roles:** guest, viewer, creator (a viewer with a creator-service record), moderator, admin.

| Journey | Status |
|---|---|
| Viewer: sign up/in (Keycloak), browse, filter, search (keyword + meaning), play HLS with quality & captions, resume, watchlist, explainable recommendations | ✅ implemented & tested |
| Creator: register, attest rights, draft, resumable direct upload, processing status, captions, submit, publish after approval, analytics, delete | ✅ |
| Moderator: queue, rights evidence, approve/reject (reason), reports, takedown, appeals; admin: health, cost, QoE, jobs | ✅ |
| Operator: independent services, traces, failure drills, backups/restore, cost guardrails | ✅ local; cloud ⛔ blocked |

**Out of scope (MVP):** payments/subscriptions (interface only in future), DRM, live streaming, series
upload UI (schema exists), email delivery (adapter disabled), paid providers (disabled adapters).

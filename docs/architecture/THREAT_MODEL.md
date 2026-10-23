# Threat model (STRIDE + OWASP API Top 10 2023)

Scope: local-core deployment as implemented. Assets: creator media and rights claims, viewer identity and
viewing history, moderation decisions, platform budget. Trust boundaries: browser ↔ gateway, browser ↔ object
storage, service ↔ service, service ↔ broker/database, worker ↔ untrusted media.

| Threat | Control (implemented) | Verified by |
|---|---|---|
| API1 BOLA / IDOR | ownership checks in every service; 404 (not 403) for others' resources | e2e (creator2 vs creator), CatalogIntegrationTest, UploadCompletionTest |
| API2 Broken auth | Keycloak OIDC, PKCE for SPA; RS256 + issuer + audience validated in every service and gateway; no local passwords | AudienceAndRolesTest, e2e bad-token 401 |
| API3 Property-level auth | public `TitleView` excludes moderation notes; mass-assignment prevented by explicit request records | code review |
| API4 Resource consumption | gateway token buckets (read/write); 1 MB request bodies; upload max size; cost guard fail-closed; FFmpeg limits/timeouts | e2e 413/415, drills |
| API5 Function-level auth | `@PreAuthorize` roles; `/internal/**` service-role only and blocked at gateway | e2e (viewer → moderation 403, admin health 403) |
| API6 Sensitive flows | idempotency keys on upload; moderators cannot decide own content; rights attestation required before upload/submit | e2e |
| API7 SSRF | services never fetch user-supplied URLs (evidence URLs are stored, not fetched); storage endpoints are config only | design |
| API8 Misconfiguration | non-root, read-only rootfs, no-new-privileges, dropped caps, CSP/HSTS/nosniff/frame-deny, private buckets | compose/Helm, e2e direct-bucket 403 |
| API9 Inventory | OpenAPI exported per service into `contracts/`; API catalog | contracts/ |
| API10 Unsafe consumption | inter-service responses typed; timeouts 2 s/5 s; failures → 503 problem | code |
| tus hook spoofing | hook endpoint not routed by gateway; constant-time shared-secret check; end user re-authenticated from the forwarded bearer JWT (signature/issuer/audience) | tus e2e (401/404/413) |
| Upload abuse / malware-as-video | server-side size + magic-byte sniff; sha256 verified in worker; ffprobe stream/size/duration limits; never executed | MediaSignatureTest, e2e corrupted cases |
| Decompression bombs | ffprobe dimension/duration/stream limits before encode; per-job timeout; memory/CPU limits; 8 GB scratch limit (k8s) | code + limits |
| Command injection via filenames/metadata | argv-only `ProcessBuilder`, env cleared; object keys server-generated | LadderAndProcTest (no shell) |
| Playlist/segment leakage | HMAC session tokens (15 min) on every playlist/segment request; 60-s presigned redirects; `Cache-Control: no-store, private`; revocation per session/title | e2e (tampered 403, revoke 403, takedown 403) |
| CDN cache poisoning | no shared caching of authorized responses (`no-store`); immutable segment objects are content-addressed by asset version | headers |
| Webhook spoofing / replay | no inbound webhooks in MVP (paid providers disabled); events are internal and deduplicated by id | design |
| Cross-tenant data exposure | per-service DB roles; per-user filters on every query | init.sh, tests |
| XSS / CSRF / CORS | Angular auto-escaping; no `innerHTML`; WebVTT rejects script/JS URLs; bearer tokens (no cookies) → CSRF N/A; CORS allow-list on API | code; Playwright |
| Token leakage | tokens stored in sessionStorage by oidc-client-ts; interceptor attaches bearer **only** to the API origin; logs never include tokens; OTel processor strips auth headers | code |
| Credential theft (services) | per-service DB passwords and client secrets via env/Secrets (local defaults only in `.env.example`); no secrets in code/logs | review, Trivy secret scan in CI |
| Supply chain | pinned versions, digest-pinned infra images, lockfiles, Trivy + SBOM in CI | ci.yml |
| Prompt injection | no LLM in the request path; embeddings only (input is text-to-vector, no instructions executed) | design |
| PII leakage | analytics stores salted user hashes only; k-anonymity (<5) for unique viewers; 180-day raw retention; history/profile erasable by user | e2e, HistoryIntegrationTest |
| Repudiation | append-only rights attestations with statement version; moderation + catalog audit trails | schema |

**Explicit non-claims:** signed URLs are access control, **not DRM** — an authorized viewer can capture
content. MinIO (local only) is unpatched upstream. No compliance certification is claimed (privacy-policy and
takedown texts are templates for the owner's legal review).

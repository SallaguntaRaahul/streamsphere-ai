# Free-Tier & License Verification

- **Verification date:** 2026-10-22 (re-check on the day of any provisioning; this file is evidence, not an entitlement)
- **Verified by:** Claude Code session, fetching official pages listed below
- **Status legend:** VERIFIED (read from the official page on the date above) · PARTIAL (official page silent on a point; third-party evidence only) · UNVERIFIED · RISK (cannot be held at $0 by a provider-enforced control)

> **Bottom line:** No provider below documents a hard, provider-enforced $0 spending cap for the
> services we would use. Oracle requires a card at sign-up; R2 very likely does too. Therefore the
> **default and fully supported deployment is local (`local-core` / `local-full`) at zero cloud cost.**
> Any cloud provisioning is blocked until the owner confirms account-level controls (see bottom).

## Cloud services

| Service | Official source | Free allowance (as read) | Payment method | Overage risk | Restrictions | Status |
|---|---|---|---|---|---|---|
| Oracle Cloud Always Free – Ampere A1 | https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm | 1,500 OCPU-h + 9,000 GB-h / month ≈ 2 OCPU + 12 GB continuously; 1–2 A1 instances | Card required for identity verification (Oracle FAQ https://www.oracle.com/ae/cloud/free/faq/ — temporary auth hold) | Upgrading to Pay-As-You-Go makes non-Always-Free resources billable. Out-of-capacity errors push toward upgrade — **do not upgrade** | Must be in **home region**; capacity not guaranteed; not in South Korea North; ARM64 only | VERIFIED (limits) / RISK |
| OCI Block Volume | same page | 200 GB total boot+block in home region; 5 volume backups; boot volume min 47–50 GB (page is internally inconsistent) | — | Volumes outside home region billed | | VERIFIED |
| OCI outbound transfer | same page | 10 TB / month | — | beyond 10 TB billable | | VERIFIED |
| OCI Object Storage | same page | 20 GB total (Always-Free-only tenancy) + 50,000 API requests/month | — | Free-Trial objects >20 GB deleted at trial end | | VERIFIED |
| OCI Load Balancer | same page | 1 Flexible LB @10 Mbps, 1 Network LB | — | | 10 Mbps is too small for video; we do **not** route media through it | VERIFIED |
| OCI idle reclamation | same page | Instance "may" be reclaimed if over 7 days: CPU p95 < 20 %, network < 20 %, memory < 20 % (A1) | — | Loss of instance (not a charge) | No notice period documented | VERIFIED / RISK (availability) |
| Cloudflare R2 Standard | https://developers.cloudflare.com/r2/pricing/ | 10 GB-month, 1 M Class A, 10 M Class B per month; egress free | Not stated on page; third-party reports say a card/PayPal is required to activate R2 | $0.015/GB-mo, $4.50/M Class A, $0.36/M Class B. **No spending cap mentioned** | Free tier is Standard class only (IA has none). Class A = Put/CreateMultipart/UploadPart/CompleteMultipart/List; Class B = Get/Head; Delete/Abort free | PARTIAL / RISK |
| Cloudflare Pages Free | https://developers.cloudflare.com/pages/platform/limits/ | 500 builds/mo, 1 concurrent, 20-min timeout, 20,000 files, 25 MiB/file, 100 projects, 100 custom domains/project | Not required for Pages (not stated on limits page) | Low (static) | `*.pages.dev` subdomain behaviour not stated on limits page | VERIFIED (limits) / PARTIAL |
| GitHub Actions | https://docs.github.com/en/billing/concepts/product-billing/github-actions | Free for **public** repos on standard runners; private (GitHub Free): 2,000 min/mo, 500 MB artifacts, 10 GB cache/repo | Without payment method usage is **blocked** at quota (good: fail-closed) | With a payment method, spend limited only by budgets you set | Linux arm64 2-core $0.005/min if billed | VERIFIED |
| Cloudflare DNS / custom domain | — | Not used. We **will not buy a domain**. Use `*.pages.dev` for the SPA; API ingress on a bare OCI IP has no free trusted TLS cert → documented limitation (see DEPLOYMENT.md) | | | | N/A |
| Email provider | — | Not used. Notifications are in-app; SMTP adapter targets local Mailpit only | | | | N/A |
| CAPTCHA | — | Not used (Keycloak brute-force detection instead) | | | | N/A |
| Hosted OIDC | — | Not used. Keycloak self-hosted (local). Cloud: Keycloak on k3s (memory-budgeted) | | | | N/A |
| AI inference API | — | Not used. Local CPU embedding model (fastembed/ONNX) | | | | N/A |
| Container registry | GHCR (https://docs.github.com/en/packages) | Public packages free; private uses the 500 MB shared storage above | | | | PARTIAL — re-verify before pushing |
| Off-node backups | — | **No approved free destination.** Backups restore locally only (documented limitation) | | | | BLOCKED |
| Mux / Cloudflare Stream / AWS MediaConvert | — | **Paid. Adapters disabled by default; no network calls in tests.** | | | | DISABLED |

## Open-source components (license / obligations)

| Component | License | Obligations / notes | Status |
|---|---|---|---|
| FFmpeg (Debian build in worker image) | LGPL-2.1+ / **GPL-2.0+** when built with libx264 (Debian's is) | Distributing the worker image = distributing GPL binaries → provide source offer/links (we use unmodified Debian packages; Debian source is the corresponding source). **H.264/AAC are patent-encumbered**; MPEG LA/Via LA licensing may apply to commercial distribution of encoders/content. We make no patent-clearance claim. | Known license; patent status UNVERIFIED legal question → owner/legal |
| x264 | GPL-2.0+ | as above | |
| k3s | Apache-2.0 | | |
| PostgreSQL | PostgreSQL License | | |
| pgvector | PostgreSQL License | | |
| RabbitMQ | MPL-2.0 | | |
| Valkey | BSD-3-Clause | | |
| Apache Kafka | Apache-2.0 | local-full only | |
| Keycloak | Apache-2.0 | | |
| MinIO | AGPL-3.0 | **Community edition archived** (Docker images stopped Oct 2025; maintenance mode Dec 2025; repo archived ~Apr 2026 — secondary sources, see ADR-0007). Used **only** locally from a digest-pinned cached image, never exposed publicly. Alternatives: SeaweedFS (Apache-2.0), RustFS (Apache-2.0), Chainguard MinIO build. | RISK (no security patches) |
| OpenTelemetry (Java/Collector) | Apache-2.0 | | |
| Prometheus | Apache-2.0 | | |
| Grafana / Loki / Tempo | AGPL-3.0 | Unmodified use; no distribution of modified versions | |
| Angular / Angular CDK | MIT | | |
| Tailwind CSS | MIT | | |
| hls.js | Apache-2.0 | | |
| oidc-client-ts | Apache-2.0 | | |
| Spring Boot / Spring Cloud | Apache-2.0 | | |
| FastAPI | MIT | | |
| fastembed | Apache-2.0 | | |
| Embedding model `BAAI/bge-small-en-v1.5` | MIT (model card) | Downloaded at image build time from Hugging Face | Verify model card before redistribution |
| MinIO-compatible AWS SDK v2 | Apache-2.0 | | |
| OpenTofu | MPL-2.0 | run via container | |
| Helm | Apache-2.0 | | |
| k6 | AGPL-3.0 | run via container, unmodified | |
| Playwright | Apache-2.0 | | |
| tusd | MIT | local-full profile | |

## Owner actions required before ANY cloud provisioning

1. Confirm the OCI tenancy is **Always-Free-only** (not upgraded) and that you will not accept the upgrade prompt on out-of-capacity errors.
2. Confirm whether R2 activation on your Cloudflare account requires a payment method; if it does, set Cloudflare **billing notifications** and accept that R2 has **no provider-enforced hard cap**. Application guardrails (COST_MODEL.md) are best-effort only.
3. Keep GitHub repo **public** or ensure no payment method is on the GitHub account (usage then blocks at quota).
4. Explicit written approval for: any `tofu apply`, any R2 bucket creation, any image push.

Until then: **cloud provisioning BLOCKED; local deployment is the deliverable.**

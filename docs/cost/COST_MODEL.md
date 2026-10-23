# Cost Model & Guardrails

> Application-side metering **estimates** provider usage. It can differ from provider billing
> (retries, SDK internals, dashboard browsing, lifecycle jobs, other apps on the same account).
> **It is not a billing cap.** Account-level controls (FREE_TIER_VERIFICATION.md §Owner actions) are mandatory.

## Unit formulas

Let for a month: `T` = titles published, `D` = average source duration (s), `L` = ABR ladder (default 360p/480p/720p), `S` = segment length (6 s), `V` = playback sessions, `W` = average watched seconds per session, `U` = uploads started.

### Storage (GB-month)
```
source_bytes        = Σ uploaded source sizes (kept until transcode READY + retention window, default 7 days, then deleted)
rendition_bytes(t)  = D_t × Σ_{r∈L} (video_kbps_r + audio_kbps) × 1000/8
   ladder kbps: 360p 800+96, 480p 1400+128, 720p 2800+128  → ≈ 5.35 Mbit per second of content ≈ 0.67 MB/s
stored_GB_month     = (Σ_t rendition_bytes(t) + live_source_bytes + posters/thumbs/captions) / 1e9 × fraction_of_month_stored
```
Example: 10 GB R2 free ≈ **4.1 hours** of 3-rung content (0.67 MB/s × 14,900 s ≈ 10 GB). The default
free-profile quota is therefore **8 GB** (80 % of 10 GB) across all creators.

### R2 Class A (writes)
```
classA_upload   = Σ_uploads (1 CreateMultipart + parts + 1 Complete) ; parts = ceil(size / 16 MiB)
classA_media    = Σ_assets  (|L| × ceil(D/S) segments + |L|+1 playlists + thumbnails(≈ D/10 + 1) + captions)
classA_list     = reconciler/orphan-cleanup ListObjects calls
```
Example: 10-min film → segments 3×100 = 300, playlists 4, thumbs 61 ≈ **365 Class A**; a 1 GiB upload ≈ 66.

### R2 Class B (reads)
```
classB_playback = Σ_sessions (1 master + 1..|L| variant playlists + ceil(W/S) segments × (1 + bitrate_switch_overhead≈0.05))
                + HEAD checks (upload verify: 1 HEAD + 1 ranged GET per upload)
classB_worker   = 1 GET per transcode (source download) + validation GETs
```
Example: 10 M Class B ≈ 10 M × 6 s = **16,600 hours watched** per month. Not the binding constraint; storage is.

### FFmpeg CPU-minutes
```
cpu_seconds ≈ D × Σ_{r∈L} k_r ,  k (libx264 veryfast, measured locally – see docs/benchmarks) 
```
On OCI A1 (2 OCPU, concurrency 1) the encode queue is the throughput limit, not a cost (A1 hours are flat-free).

### Network
OCI: 10 TB/month outbound free. Media bytes are served **from R2 directly** (presigned redirects), so OCI egress ≈ API JSON + playlists only.

### GitHub CI
Public repo: $0. Private: per run ≈ 12–18 Linux minutes (Java build/test ≈ 8, web ≈ 4, images ≈ 6) → ~110 runs within 2,000 min.

## Guardrails implemented (upload service `CostGuard`)

| Threshold of app-estimated quota | Behaviour |
|---|---|
| 50 % | `INFO` log + metric `streamsphere_cost_quota_ratio` |
| 70 % | `WARN` log, admin dashboard banner |
| 85 % | `WARN` + notification to admins |
| 95 % (safety threshold, configurable `COST_GUARD_STOP_RATIO`) | **New uploads rejected** with `409 quota-exhausted` (fail-closed) |

Also fail-closed: if the meter cannot be read (DB down) uploads are refused rather than allowed.
Paid adapters (Mux, Cloudflare Stream, MediaConvert) refuse to construct unless `PAID_PROVIDERS_ENABLED=true` **and** a provider-specific flag is set; both default `false` and are asserted in tests.

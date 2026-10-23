# Reliability: SLO targets (NOT achievements) and evidence

| SLO target | Measured (local, see benchmarks) | Notes |
|---|---|---|
| API availability 99.5 % monthly | not measurable locally | single node → no HA; pod restart caused ~9–12 s of errors |
| Playback authorization p95 < 300 ms | **205.6 ms p95** (k6, 5 VUs, 60 s) ; p99 1.12 s | p99 outliers from cold JIT (`TieredStopAtLevel=1`) |
| HLS startup p95 < 4 s | real beacons exist; no controlled-network test yet | measured per session by the SPA → analytics |
| Rebuffer ratio < 1 % | no controlled-network test yet | local playback showed 0 rebuffer in Playwright run |

Resilience mechanisms and the drills that exercise them: see MEDIA_PIPELINE.md and
`tests/resilience/drills.py` (5/5 PASS). Backups: `scripts/backup.sh` + `scripts/restore-drill.sh` (12/12
databases restored with identical per-table row counts); **no off-node backup destination** yet.

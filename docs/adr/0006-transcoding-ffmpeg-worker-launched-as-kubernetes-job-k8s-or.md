# ADR-0006: Transcoding: FFmpeg worker, launched as Kubernetes Job (k8s) or queue consumer (Compose)

- Status: Accepted · Date: 2026-10-22

## Context
Real HLS ABR output is mandatory; free profile has 2 OCPUs.

## Options
(a) FFmpeg in media service JVM; (b) separate worker image, Kubernetes Job per asset; (c) managed transcoders (paid).

## Decision
`workers/ffmpeg` (Java 21 + Debian ffmpeg) executes: download → sha256 verify → ffprobe → source-aware ladder (360/480/720p; 1080p opt-in) → libx264/AAC with fixed GOP = 2×fps keyframes aligned to 6-s segments, `-sc_threshold 0` → HLS VOD variant playlists + master → poster + thumbnails → validate (ffprobe every variant, segment count/duration checks) → upload to immutable `assets/{assetId}/v{n}/` prefix → report result. `media` service decides launch mode via `TranscodingProvider`: `k8s-job` (fabric8 creates a bounded Job, `activeDeadlineSeconds`, `backoffLimit 1`) or `queue` (Compose: worker consumes `media.transcode.requests`, prefetch 1). Command arguments are built as argv arrays — no shell.

## Rationale
Isolates crashy/CPU-heavy work, bounded concurrency, same image in both modes.

## Consequences
Worker image is GPL (FFmpeg/x264) — see license notes.

## Revisit Trigger
Need for GPU or managed transcoding.

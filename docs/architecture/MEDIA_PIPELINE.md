# Media pipeline (implemented)

```mermaid
sequenceDiagram
  participant B as Browser
  participant U as upload
  participant S as Object storage
  participant M as media
  participant W as ffmpeg-worker / k8s Job
  participant C as catalog
  participant Mo as moderation
  participant P as playback
  B->>U: POST /uploads (title, size, type, sha256) + Idempotency-Key
  U->>U: ownership (catalog API), attestation (creator API), cost guard
  U->>S: CreateMultipartUpload
  loop each 8 MiB part (3 parallel, resumable)
    B->>U: presign part n
    B->>S: PUT part (presigned, 15 min)
  end
  B->>U: complete(parts+ETags)
  U->>S: ListParts (truth) → Complete → HEAD size → ranged GET magic bytes
  U-->>M: UploadCompleted.v1 (outbox)
  M->>M: asset v(n), job attempt 1, prefix assets/{id}/v{n}/a{k}/
  M-->>W: TranscodeRequested.v1 (or Kubernetes Job)
  W->>S: download, verify sha256, ffprobe limits
  W->>W: libx264/AAC ladder (≤720p), 2-s GOP, 6-s HLS VOD segments, poster, thumbnails
  W->>W: validate playlists, segment durations, keyframe at segment start
  W->>S: upload output, then _COMPLETE marker LAST
  W-->>M: TranscodeCompleted.v1
  M->>S: HEAD _COMPLETE (never trust partial output)
  M-->>C: MediaReady.v1 → catalog media_state READY
  B->>C: submit → ContentSubmitted.v1 → moderation case
  Mo-->>C: ContentApproved.v1 → creator publishes → ContentPublished.v1
  B->>P: POST /playback/sessions → HMAC token (15 min)
  B->>P: master/variant playlists (generated / proxied, no-store)
  B->>P: segment → 302 presigned GET (60 s) → S
```

## States
- Upload: `INITIATED → UPLOADING → UPLOADED | FAILED | CANCELLED | EXPIRED`
- Media (processing): `QUEUED → PROCESSING → READY | FAILED`, `DELETED` on takedown; job: `QUEUED → RUNNING → SUCCEEDED | FAILED | TIMED_OUT`
- Catalog (editorial, separate): `DRAFT → IN_REVIEW → APPROVED → PUBLISHED`, `REJECTED` (edit/resubmit/appeal), `REMOVED` (terminal)

## Failure handling (each verified — see docs/benchmarks/2026-10-22-local.md)
| Failure | Mechanism | Evidence |
|---|---|---|
| Worker killed mid-encode | request un-acked → redelivered → re-run; reconciler fails overdue jobs and retries (max 3 attempts, fresh prefix each) | drill PASS |
| Duplicate UploadCompleted / results | inbox dedup; `media_assets.upload_id` unique; job state checks | unit/integration + e2e |
| Corrupted / non-video input | magic sniff at completion (422, object deleted); ffprobe in worker → non-retryable FAILED | e2e PASS |
| Partial HLS output | validator before upload; `_COMPLETE` written last and checked before READY | unit tests |
| Abandoned multipart | reconciler aborts after 24 h | code + unit |
| Takedown / creator deletion | ContentRemoved → revoke sessions, delete all asset prefixes, remove from search/catalog, delete source | e2e PASS |
| Broker restart | outbox keeps events; relays/consumers reconnect | drill PASS |

**Upload protocols** (`UploadProvider`): S3 multipart (SPA default) and **tus 1.0** via tusd (`pre-create` hook = auth/ownership/rights/quota admission, `post-finish` hook = same object verification + `UploadCompleted.v1`). Both share `UploadService.verifyAndPublish`.

FFmpeg arguments are built as argv arrays (no shell; unit-tested). Limits: ≤3 h, ≤4096×2304, ≤16 streams, per-job wall timeout, worker CPU ≤2, memory ≤1.5 GB.

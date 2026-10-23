# ADR-0007: Object storage: S3-compatible StorageProvider; MinIO archived → local-only

- Status: Accepted · Date: 2026-10-22

## Context
Prompt specifies MinIO locally and R2 in cloud. MinIO community edition stopped publishing images (Oct 2025) and is archived (2026).

## Options
(a) Keep MinIO from a cached digest-pinned image, local only; (b) SeaweedFS; (c) RustFS; (d) Chainguard-built MinIO.

## Decision
(a) for now: `minio/minio@sha256:14cea493…` (built 2025-09-07) bound to localhost only. All code uses AWS SDK v2 against a configurable endpoint (`StorageProvider` = S3/R2/MinIO), so swapping to SeaweedFS/RustFS is configuration. R2 used in oci-free.

## Rationale
Zero new download, proven multipart/presign/CORS behaviour, exact R2 API surface.

## Consequences
No security patches for MinIO — never expose it beyond localhost.

## Revisit Trigger
Any MinIO CVE affecting local use, or when a replacement passes the storage contract test suite.

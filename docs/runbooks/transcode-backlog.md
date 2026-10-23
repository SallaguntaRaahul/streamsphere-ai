# Runbook: Transcode backlog

**Trigger:** Alert `TranscodeQueueBacklog`.

1. Worker alive? `docker compose ps ffmpeg-worker` / `kubectl -n streamsphere get jobs`. 2. Stuck jobs are failed by the media reconciler after their deadline and retried (max 3). 3. Concurrency is 1 by design on the free node; scale only with spare CPU.

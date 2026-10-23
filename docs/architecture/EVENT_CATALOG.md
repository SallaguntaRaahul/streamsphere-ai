# Event catalog

Envelope (`contracts/events/envelope.schema.json`): `eventId, eventType, eventVersion, aggregateId, occurredAt,
correlationId, producer, payload`. Exchange `streamsphere.events` (topic), routing key `<EventType>.v<N>`.
Delivery is **at-least-once**; every consumer deduplicates by `eventId` in the same DB transaction as its side
effects. Retry: per-service `<svc>.retry` queue (TTL backoff with jitter) → `<svc>.dlq` after 5 attempts;
replay with `scripts/dlq-replay.sh <svc>`. Exactly-once business semantics are **not** claimed.

| Event (v1) | Producer | Consumers |
|---|---|---|
| UploadCompleted | upload | media |
| TranscodeRequested | media | ffmpeg-worker |
| TranscodeStarted | ffmpeg-worker | media |
| TranscodeCompleted | ffmpeg-worker | media |
| TranscodeFailed | ffmpeg-worker | media |
| MediaProcessingStarted | media | catalog |
| MediaReady | media | catalog, upload, notifications |
| MediaProcessingFailed | media | catalog, notifications |
| MediaAssetsDeleted | media | upload |
| ContentSubmitted | catalog | moderation |
| ContentApproved | moderation | catalog, notifications |
| ContentRejected | moderation | catalog, notifications |
| ContentPublished | catalog | search, recommendations, analytics, media, moderation, notifications |
| ContentRemoved | catalog | moderation | catalog, media, playback, search, recommendations, history, upload, moderation, notifications |
| CreatorRegistered | creator | — |
| PlaybackStarted | playback | analytics, search, recommendations |
| PlaybackProgressed | history | analytics, recommendations |
| PlaybackCompleted | history | analytics, recommendations |

Schemas: `contracts/events/<Event>.v1.schema.json`. Breaking changes → new version (`.v2`) published in parallel.

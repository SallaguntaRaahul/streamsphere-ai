# Data ownership

One PostgreSQL server, **one database + one login role per service** (`infrastructure/docker/postgres/init.sh`,
`REVOKE CONNECT ... FROM PUBLIC`). No service can connect to another's database. Each owns its Flyway
migrations (`src/main/resources/db/migration`) plus its own copy of `outbox_events` / `inbox_deduplication`.

| Database | Tables (owner) | Projections it keeps (from events) |
|---|---|---|
| catalog | titles, seasons, episodes, audit_events | media_state/asset/version/duration from Media* events |
| creator | creators, rights_attestations (append-only) | — |
| profile | profiles | — |
| upload | upload_sessions, upload_parts, cost_meter | media_storage (bytes from MediaReady) |
| media | media_assets, processing_jobs, renditions, caption_tracks | published/removed flags |
| playback | playback_sessions, revoked_titles, delivery_counters | revocations from ContentRemoved |
| history | watch_progress, watchlists | removed_titles |
| search | search_documents (tsvector + vector(384)) | from ContentPublished/Removed/PlaybackStarted |
| recommendations | embeddings, interactions, recommendation_runs | from Content* and Playback* events |
| moderation | review_cases, content_reports, audit_events | title_projection |
| analytics | playback_events, creator_daily_stats | title_owners |
| notifications | notifications, delivery_attempts | — |

Object storage: `streamsphere-uploads` (sources; upload service) and `streamsphere-media`
(immutable `assets/{assetId}/v{n}/a{attempt}/`; media service + worker). Both private.

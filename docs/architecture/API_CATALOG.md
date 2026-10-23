# API catalog (generated from the services' live OpenAPI documents)

All APIs are under `/api/v1`, JSON, errors are `application/problem+json`, auth is OIDC bearer (audience `streamsphere-api`).
`/api/v1/internal/**` endpoints require the `service` role and are **not routed** by the gateway. Full specs: `contracts/openapi/*.json` (TS types generated into the SPA).

## analytics
- `GET /api/v1/analytics/admin/qoe`
- `GET /api/v1/analytics/creator/me/titles`
- `POST /api/v1/analytics/qoe`
## catalog
- `DELETE /api/v1/creator/titles/{id}`
- `GET /api/v1/catalog/admin/titles`
- `GET /api/v1/creator/titles`
- `GET /api/v1/creator/titles/{id}`
- `GET /api/v1/genres`
- `GET /api/v1/internal/titles/{id}`
- `GET /api/v1/titles`
- `GET /api/v1/titles/batch`
- `GET /api/v1/titles/{id}`
- `POST /api/v1/creator/titles`
- `POST /api/v1/creator/titles/{id}/publish`
- `POST /api/v1/creator/titles/{id}/submit`
- `PUT /api/v1/creator/titles/{id}`
## creator
- `GET /api/v1/creators/me`
- `GET /api/v1/creators/me/attestations`
- `GET /api/v1/creators/{userId}`
- `GET /api/v1/internal/creators/{userId}/attestations`
- `POST /api/v1/creators`
- `POST /api/v1/creators/me/attestations`
- `POST /api/v1/internal/creators/{userId}/suspend`
- `PUT /api/v1/creators/me`
## history
- `DELETE /api/v1/history`
- `DELETE /api/v1/watchlist/{titleId}`
- `GET /api/v1/history`
- `GET /api/v1/history/continue-watching`
- `GET /api/v1/history/progress/{titleId}`
- `GET /api/v1/watchlist`
- `PUT /api/v1/history/progress`
- `PUT /api/v1/watchlist/{titleId}`
## media
- `DELETE /api/v1/media/assets/{id}/captions/{language}`
- `GET /api/v1/internal/assets/{id}/manifest`
- `GET /api/v1/media/admin/jobs`
- `GET /api/v1/media/assets/{id}`
- `GET /api/v1/media/assets/{id}/poster`
- `GET /api/v1/media/titles/{titleId}/assets`
- `POST /api/v1/media/assets/{id}/captions`
## moderation
- `GET /api/v1/moderation/audit`
- `GET /api/v1/moderation/cases/{id}`
- `GET /api/v1/moderation/queue`
- `POST /api/v1/moderation/appeals`
- `POST /api/v1/moderation/cases/{id}/decision`
- `POST /api/v1/moderation/reports`
- `POST /api/v1/moderation/takedowns`
## notifications
- `GET /api/v1/notifications`
- `GET /api/v1/notifications/unread-count`
- `POST /api/v1/notifications/read-all`
- `POST /api/v1/notifications/{id}/read`
## playback
- `GET /api/v1/playback/admin/stats`
- `GET /api/v1/playback/stream/{token}/master.m3u8`
- `GET /api/v1/playback/stream/{token}/subs/{file}`
- `GET /api/v1/playback/stream/{token}/v/{rendition}/index.m3u8`
- `GET /api/v1/playback/stream/{token}/v/{rendition}/{segment}`
- `POST /api/v1/playback/admin/titles/{titleId}/revoke`
- `POST /api/v1/playback/sessions`
- `POST /api/v1/playback/sessions/{id}/revoke`
## profile
- `DELETE /api/v1/profiles/me`
- `GET /api/v1/profiles/me`
- `PUT /api/v1/profiles/me`
## recommendations
- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
- `GET /api/v1/recommendations`
- `GET /api/v1/recommendations/similar/{title_id}`
- `POST /internal/embed`
## search
- `GET /api/v1/search`
- `GET /api/v1/search/suggest`
## upload
- `DELETE /api/v1/uploads/{id}`
- `GET /api/v1/uploads`
- `GET /api/v1/uploads/admin/cost`
- `GET /api/v1/uploads/quota`
- `GET /api/v1/uploads/{id}`
- `POST /api/v1/internal/tus/hooks`
- `POST /api/v1/uploads`
- `POST /api/v1/uploads/{id}/complete`
- `POST /api/v1/uploads/{id}/parts/{partNumber}/presign`

## tus (tusd, local-full / any host)
- `POST/HEAD/PATCH /files/` (tus 1.0) on :1080 — admission & verification via `POST /api/v1/internal/tus/hooks` (shared secret + forwarded user JWT)

## analytics (local-full, `ANALYTICS_KAFKA_ENABLED=true`)
- `POST /api/v1/analytics/admin/stream/replay?rebuild=` (admin) — replay the Kafka `playback-events` log from offset 0 with a new consumer group

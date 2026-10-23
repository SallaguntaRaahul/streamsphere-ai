# Runbook: Playback authorization slow

**Trigger:** Alert `PlaybackAuthSlow`.

1. Check catalog/media latency (playback calls both with a service token). 2. Valkey down → revocation checks fall back to PostgreSQL (slower but correct). 3. Keycloak token endpoint latency affects first call after token expiry.

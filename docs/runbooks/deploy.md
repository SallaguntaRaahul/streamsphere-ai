# Runbook: deploy StreamSphere on a Docker host

Requirements: Docker 24+ with Compose v2, ≥ 8 GB RAM for the containers, ports 8080/8180/9000/4200 (or a reverse proxy).

## 1. Get images
Either build locally (`make images`) or use the CI-published images (repository → Actions → **ci** →
*Run workflow* with `push_images=true`; free for public repos), then:
```bash
export IMAGE_PREFIX=ghcr.io/sallaguntaraahul IMAGE_NAME_PREFIX=streamsphere- IMAGE_TAG=latest
docker compose pull   # then: docker compose up -d --no-build
```
Compose resolves `${IMAGE_PREFIX}/${IMAGE_NAME_PREFIX}<service>:${IMAGE_TAG}`; defaults build local `streamsphere/<service>:local` images.

## 2. Configure (`.env`, never commit)
Copy `.env.example` → `.env` and replace **every** local default: `POSTGRES_SUPERUSER_PASSWORD`, `RABBITMQ_PASSWORD`,
`MINIO_ROOT_PASSWORD`, `KEYCLOAK_ADMIN_PASSWORD`, `PLAYBACK_TOKEN_SECRET` (`openssl rand -hex 32`), `TUS_HOOK_SECRET`,
`ANALYTICS_USER_HASH_SALT`; per-service DB passwords are set by `infrastructure/docker/postgres/init.sh`
(`DB_PASSWORD_<SERVICE>` env) and must match each service's `DB_PASSWORD`. Rotate Keycloak client secrets
(`svc-*`) and delete the seeded demo users from `streamsphere-realm.json` before a public deployment.

For a public hostname set: `PUBLIC_API_BASE=https://api.<host>`, `PUBLIC_OIDC_AUTHORITY=https://auth.<host>/realms/streamsphere`,
`KC_HOSTNAME` (compose) to the public auth URL, the web client's redirect URIs/web origins in the realm, gateway
`CORS_ORIGINS`, and storage `STORAGE_PUBLIC_ENDPOINT` + bucket CORS. Terminate TLS in a reverse proxy (Caddy/Traefik);
never expose MinIO publicly — use R2 or a maintained S3 store for internet-facing deployments (ADR-0007).

## 3. Start & verify
```bash
docker compose up -d && ./scripts/wait-healthy.sh
python3 tests/e2e/run_media_pipeline.py    # 20/20 expected
```
Optional: `docker compose --profile full up -d` (tusd, Kafka, observability).

## Kubernetes
Per-service Helm values in `infrastructure/helm/values/`, chart `infrastructure/helm/streamsphere-service`
(`helm upgrade --install <svc> infrastructure/helm/streamsphere-service -f infrastructure/helm/values/<svc>.yaml`), with
Secrets created out-of-band (names in each values file). Data services (PostgreSQL, RabbitMQ, Valkey, Keycloak) must be
provided by the cluster operator. Media uses Kubernetes Jobs for FFmpeg (`TRANSCODE_LAUNCHER=k8s-job`, verified on k3s).

## Rollback
Re-deploy the previous image tag (`IMAGE_TAG=<sha>`); migrations are expand-then-contract, so N-1 runs against schema N.

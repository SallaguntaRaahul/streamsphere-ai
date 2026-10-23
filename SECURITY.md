# Security policy

Report vulnerabilities privately to the maintainer (Raahul Sallagunta) via GitHub Security Advisories on this
repository — please do not open public issues. Include reproduction steps and affected version/commit.

Scope notes: credentials in `infrastructure/docker/keycloak/streamsphere-realm.json` and `.env.example` are
**local test values** for the developer stack only. MinIO is used locally only (archived upstream). Signed media
URLs are access control, not DRM. Threat model: `docs/architecture/THREAT_MODEL.md`.

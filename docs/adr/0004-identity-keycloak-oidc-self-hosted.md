# ADR-0004: Identity: Keycloak (OIDC) self-hosted

- Status: Accepted · Date: 2026-10-22

## Context
Real OIDC provider required; no homemade password auth; free.

## Options
(a) Keycloak; (b) hosted OIDC free tiers (Auth0/Clerk/etc. – quota and terms vary, need card checks); (c) Spring Authorization Server (would mean we own password storage/UI).

## Decision
Keycloak 26 (realm import `infrastructure/docker/keycloak/streamsphere-realm.json`). SPA = public client with Authorization Code + PKCE. Each backend service validates JWT signature (JWKS), issuer and audience `streamsphere-api`. Service-to-service calls use confidential clients with client-credentials and realm role `service`. Realm roles: viewer (default), moderator, admin. **Creator capability is a creator-service record** (registration + rights attestation), not a token role, so self-registration never needs an admin API.

## Rationale
Battle-tested, Apache-2.0, no external quota, works offline.

## Consequences
~500–700 MB RAM; on oci-free it consumes ~6 % of memory budget (see CAPACITY_PLAN.md). Data residency = wherever we run it.

## Revisit Trigger
Memory pressure on OCI, or a verified $0 hosted provider with acceptable terms.

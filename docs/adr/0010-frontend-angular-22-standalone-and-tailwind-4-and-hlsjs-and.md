# ADR-0010: Frontend: Angular 22 standalone + Tailwind 4 + hls.js + oidc-client-ts

- Status: Accepted · Date: 2026-10-22

## Context
Prompt requires Angular, Tailwind, CDK, strict TS, hls.js with Safari fallback, typed clients from OpenAPI.

## Options
OIDC lib: angular-oauth2-oidc vs oidc-client-ts; client gen: openapi-generator (Java tool) vs openapi-typescript.

## Decision
Angular 22 standalone components + signals; oidc-client-ts (PKCE); openapi-typescript generates types from `contracts/openapi/*.json` exported by each service; thin typed fetch wrapper. Static build deployable to Cloudflare Pages.

## Rationale
Smallest dependency set, no JVM needed for codegen.

## Consequences
Hand-written thin client code over generated types.

## Revisit Trigger
If generated full clients become necessary.

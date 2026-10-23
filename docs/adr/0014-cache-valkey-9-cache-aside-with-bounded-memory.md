# ADR-0014: Cache: Valkey 9 cache-aside with bounded memory

- Status: Accepted · Date: 2026-10-22

## Context
Reduce catalog read load; fast revocation checks.

## Options
Valkey vs Redis (license change) vs in-process.

## Decision
Valkey (BSD-3) `maxmemory 64mb allkeys-lru`. Catalog list/detail cache TTL 60 s, invalidated on lifecycle change; playback revocation set (no TTL, rebuilt from DB). All cache calls are wrapped: on failure we fall back to the database. Authorization decisions are not cached.

## Rationale
BSD license, drop-in.

## Consequences
—

## Revisit Trigger
—

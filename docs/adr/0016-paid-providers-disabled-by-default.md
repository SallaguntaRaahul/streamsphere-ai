# ADR-0016: Paid providers disabled by default

- Status: Accepted · Date: 2026-10-22

## Context
Mux, Cloudflare Stream, MediaConvert are paid.

## Options
Omit vs implement behind flags.

## Decision
Implement adapters behind `PAID_PROVIDERS_ENABLED=false` + per-provider flag; constructors throw if flags are off; tests use mocks only. Asset records store `provider` + `provider_version`; no silent migration.

## Rationale
Shows extensibility without spending.

## Consequences
Untested against real APIs (explicitly).

## Revisit Trigger
Owner approves a sandbox budget.

# Contributing

- JDK 21, Maven 3.9, Docker. Node is only needed inside the `node:22` container (Angular 22).
- `make build` · `make test` (unit + Testcontainers) · `make up` · `make e2e` · browser tests: `cd tests/e2e-web && npx playwright test`.
- Every service owns its schema: add a new Flyway migration (never edit an applied one); expand-then-contract for breaking changes.
- Events are versioned: add `<Event>.v2` rather than changing `v1`; update `contracts/events` and EVENT_CATALOG.md.
- No shared domain models in `packages/technical-common`.
- Never commit secrets; never enable paid providers or apply infrastructure without owner approval.

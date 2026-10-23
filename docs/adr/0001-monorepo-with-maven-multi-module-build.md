# ADR-0001: Monorepo with Maven multi-module build

- Status: Accepted · Date: 2026-10-22

## Context
Thirteen Java services, a worker, a Python service and an Angular app must be independently buildable and deployable while sharing technical (non-domain) code and contracts.

## Options
(a) Polyrepo per service; (b) Monorepo + Gradle; (c) Monorepo + Maven aggregator with per-module poms.

## Decision
Monorepo. Maven 3.9 aggregator `pom.xml`; each service has its own `pom.xml` and is built alone with `mvn -pl services/<svc> -am package`. Shared code limited to `packages/technical-common` (problem details, correlation IDs, JWT validation, outbox/inbox, messaging plumbing). No shared domain models.

## Rationale
Maven is already installed and pinned (3.9.16); declarative, reproducible, simple for reviewers. Polyrepo adds release overhead with one maintainer.

## Consequences
Common technical library is versioned with the repo; a breaking change there forces rebuilding dependants (acceptable, CI builds all).

## Revisit Trigger
More than one team owns services, or build time > 10 min.

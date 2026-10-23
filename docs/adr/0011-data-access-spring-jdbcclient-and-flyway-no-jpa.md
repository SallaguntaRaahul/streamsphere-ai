# ADR-0011: Data access: Spring JdbcClient + Flyway (no JPA)

- Status: Accepted · Date: 2026-10-22

## Context
13 JVMs must fit an 8 GB laptop Docker VM and a 12 GB node.

## Options
JPA/Hibernate vs Spring Data JDBC vs JdbcClient.

## Decision
JdbcClient with explicit SQL, optimistic locking via `version` columns, `ON CONFLICT` upserts.

## Rationale
Lower memory/startup, SQL is explicit and reviewable.

## Consequences
More boilerplate mapping.

## Revisit Trigger
Complex aggregates appear.

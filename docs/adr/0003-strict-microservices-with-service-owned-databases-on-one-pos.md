# ADR-0003: Strict microservices with service-owned databases on one PostgreSQL server

- Status: Accepted · Date: 2026-10-22

## Context
Prompt mandates independent services, own persistence and credentials, no cross-service joins, on a 2-OCPU/12-GB free node.

## Options
(a) Database per PostgreSQL server; (b) one server, database per service with its own role; (c) schema-per-service in one database.

## Decision
(b) One PostgreSQL 16 (+pgvector) server, **one database and one login role per service** created by `infrastructure/docker/postgres/init.sql`. Each role owns only its DB (`REVOKE CONNECT ... FROM PUBLIC`). Each service runs its own Flyway migrations. Data needed across services travels via REST APIs (with user token relay or client-credentials service token) or via event-fed local projections.

## Rationale
Database-level isolation is enforced by PostgreSQL permissions, not convention, while fitting one small server.

## Consequences
Single PostgreSQL is a shared failure domain (no HA claims). Cross-service reads need projections → eventual consistency.

## Revisit Trigger
A service needs different scaling/HA than others, or a second node exists.

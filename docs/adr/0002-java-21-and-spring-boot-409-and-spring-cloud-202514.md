# ADR-0002: Java 21 + Spring Boot 4.0.9 + Spring Cloud 2025.1.4

- Status: Accepted · Date: 2026-10-22

## Context
Prompt requires Java 21 LTS and a current stable Spring Boot. On 2026-10-22 Maven Central shows Boot 4.1.2 GA but its Spring Cloud train (2026.0) is only RC1.

## Options
(a) Boot 4.1.2 + Cloud 2026.0.0-RC1; (b) Boot 4.0.9 + Cloud 2025.1.4 (both GA, officially compatible); (c) Boot 3.5.x (OSS support ended 2026-06).

## Decision
(b) Boot 4.0.9 / Spring Cloud 2025.1.4 (Gateway 5.0.x, server-webmvc flavour), Java 21 toolchain.

## Rationale
Only GA, mutually compatible versions. Boot 4 brings Jackson 3, modular starters, built-in OpenTelemetry starter and structured logging.

## Consequences
Must move to 4.1 before 4.0 OSS end-of-life.

## Revisit Trigger
Spring Cloud 2026.0 GA, or Boot 4.0 OSS EOL.

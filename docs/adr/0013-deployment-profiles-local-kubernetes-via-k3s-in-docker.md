# ADR-0013: Deployment profiles; local Kubernetes via k3s-in-Docker

- Status: Accepted · Date: 2026-10-22

## Context
Owner chose 'containers only' – no host installs of k3d/kind.

## Options
Docker Desktop Kubernetes, kind/k3d binaries, `rancher/k3s` container.

## Decision
`local-core` and `local-full` on Docker Compose (primary). Kubernetes profile uses the `rancher/k3s` image run directly by `scripts/k8s-local-up.sh` (same as k3d does) + host kubectl/helm. Helm chart `infrastructure/helm/streamsphere-service` is reused by every service with per-service values.

## Rationale
No new host binaries; identical k3s to OCI.

## Consequences
Script owns cluster lifecycle.

## Revisit Trigger
—

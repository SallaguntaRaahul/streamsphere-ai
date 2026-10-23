#!/usr/bin/env bash
# Local Kubernetes without extra host binaries (ADR-0013): runs k3s itself in Docker (what k3d does),
# attached to the compose network so Jobs can reach local MinIO/RabbitMQ. Writes a repo-local kubeconfig.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=streamsphere-k3s
IMAGE=${K3S_IMAGE:-rancher/k3s:v1.36.5-k3s1}
KCFG=infrastructure/kubernetes/.kubeconfig
if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
  docker run -d --name "$NAME" --privileged --network streamsphere_default --hostname "$NAME" \
    -p 127.0.0.1:16443:6443 --tmpfs /run --tmpfs /var/run "$IMAGE" server \
    --disable traefik --disable metrics-server --disable servicelb --tls-san 127.0.0.1 --tls-san "$NAME" >/dev/null
fi
until docker exec "$NAME" kubectl get nodes 2>/dev/null | grep -q ' Ready'; do sleep 2; done
docker exec "$NAME" cat /etc/rancher/k3s/k3s.yaml | sed 's#127.0.0.1:6443#127.0.0.1:16443#' > "$KCFG"
docker exec "$NAME" cat /etc/rancher/k3s/k3s.yaml | sed "s#127.0.0.1:6443#$NAME:6443#" > "$KCFG.incluster"
echo "k3s ready. export KUBECONFIG=$PWD/$KCFG"

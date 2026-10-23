# Runbook: High 5xx rate

**Trigger:** Alert `HighErrorRate`.

1. Grafana overview → which service. 2. Take a correlation id from a failing problem response; `docker compose logs <svc> | grep <id>`. 3. 503 `dependency-unavailable` / `storage-unavailable` = downstream outage (check that service); 500 = bug — roll back the image.

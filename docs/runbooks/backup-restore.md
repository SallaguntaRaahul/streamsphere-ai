# Runbook: Backup & restore

**Trigger:** Routine / incident.

Backup: `scripts/backup.sh` (pg_dump per service DB + SHA256SUMS, local only). Drill: `scripts/restore-drill.sh backups/<ts>` restores into scratch DBs and compares per-table row counts (never touches live data). Object storage is not backed up (no free off-node destination) — documented limitation.

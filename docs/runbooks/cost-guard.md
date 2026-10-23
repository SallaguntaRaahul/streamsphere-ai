# Runbook: Cost guard warning / uploads paused

**Trigger:** Alerts `CostQuotaWarning` (≥70 %) / `CostQuotaStop` (≥95 %).

1. Admin → Operations: storage ratio & ops. 2. Delete abandoned drafts / old asset versions; sources are auto-deleted 7 days after READY. 3. Only the owner may raise `STORAGE_QUOTA_BYTES` and only after re-checking provider billing (FREE_TIER_VERIFICATION.md). The guard is an estimate, not a billing cap.

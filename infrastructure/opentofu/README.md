# OpenTofu — oci-free profile (PLAN ONLY)

Creates one Always Free Ampere A1 VM (≤2 OCPU, ≤12 GB, ≤200 GB boot) with k3s, a VCN, a public subnet and a
security list (443 public, 22 operator-only). Variable validations make paid sizes impossible to request.

```bash
cd infrastructure/opentofu/oci-free
tofu init && tofu plan -var-file=owner.tfvars   # review only
```

**Never `tofu apply` without the owner's written approval**, after completing the checklist in
`docs/cost/FREE_TIER_VERIFICATION.md` (Always-Free-only tenancy, no Pay-As-You-Go upgrade). Status on
2026-10-22: **not applied** — no credentials provided; `fmt`/`validate` run in CI only.

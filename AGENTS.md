# Agent notes

Personal Guix configuration for an M1 MacBook Air; the installed system boots from the internal Btrfs root and EFI system partition.

- Keep `local/` private and ignored. Do not commit firmware, credentials, password hashes or machine backups.
- Author Scheme in `modules/` and mutable application configs in `desktop/configs/<app>`. `desktop/stow-home` links those configs into their live Home locations; keep build-time assets outside `desktop/configs/`.
- Use jj, not git. Scheme evaluations require Guix and the pinned Asahi channel. See `README.md` for workflows.
- Pi discovers the project Guix skill at `.agents/skills/guix/SKILL.md`; use it for Guix-specific tasks and its `references.md` for local/manual links.

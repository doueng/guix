# Agent notes

Personal Guix configuration for an M1 MacBook Air; the installed system boots from the internal Btrfs root and EFI system partition.

- Keep `local/` private and ignored. Do not commit firmware, credentials, password hashes or machine backups.
- Author Scheme in `modules/` and desktop assets in `desktop/`. `desktop/shared/` is independently maintained
- Use jj, not git. Scheme evaluations require Guix and the pinned Asahi channel. See `README.md` and `desktop/README.md` for workflows.
- Pi discovers the project Guix skill at `.agents/skills/guix/SKILL.md`; use it for Guix-specific tasks and its `references.md` for local/manual links.

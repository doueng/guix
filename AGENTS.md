# Agent notes

Personal Guix configuration for an M1 MacBook Air; the installed system boots from the internal Btrfs root and EFI system partition.

- Read `PROGRESS.md` before installation or recovery work. Never rerun formatting or `guix system init` as a resume step.
- Prefer offline checks. Mounting, activation, bootloader/ESP writes and destructive operations require explicit approval. Preserve the protected NixOS ESP and recovery environment; never disable channel authentication.
- Keep `local/` private and ignored. Do not commit firmware, credentials, password hashes or machine backups.
- Author Scheme in `modules/` and desktop assets in `desktop/`. `desktop/shared/` is independently maintained; it does not sync from NixOS.
- Use jj, not git. Run `make test`; Scheme evaluations require Guix and the pinned Asahi channel. See `README.md` and `desktop/README.md` for workflows.
- Pi discovers the project Guix skill at `.agents/skills/guix/SKILL.md`; use it for Guix-specific tasks and its `references.md` for local/manual links.
- NixOS bootstrap and AWS infrastructure belong to `~/nixos`, not this repository.

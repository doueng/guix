# Agent notes

Personal Guix configuration for an Apple M1 MacBook Air with Samsung T7 USB root.

- Read `PROGRESS.md` before installation work. The SSD is already initialized; do not rerun formatting or `guix system init`.
- Prefer offline tests, evaluation and builds. Mounting, activation, bootloader/ESP writes and destructive operations require explicit approval.
- Preserve the protected NixOS ESP and the independent recovery environment. Never disable channel authentication.
- Keep `local/` private and ignored. Do not commit firmware, credentials, password hashes or machine backups.
- Author Scheme in `modules/`, desktop assets in `desktop/`. `desktop/shared/` is independently owned here, not synced automatically from NixOS.
- Use jj, not git. Keep comments minimal.
- Run `make test`; optional editor/Fish checks require host tools. Scheme checks require Guix and the pinned Asahi channel modules; see README.md and desktop/README.md.
- NixOS bootstrap and AWS infrastructure are owned by `~/nixos`, not this repository.

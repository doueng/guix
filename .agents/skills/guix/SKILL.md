---
name: guix
description: Help with GNU Guix package management, channels, system configuration, services, Scheme, and troubleshooting. Use when working on this Guix config or asking about Guix commands and APIs.
---

# GNU Guix

Use the GNU Guix System manual and cookbook as the primary references. They evolve with Guix, so verify version-sensitive commands and APIs against the installed Guix and its documentation rather than relying on memory.

## References

- [GNU Guix System manual (development version)](https://guix.gnu.org/manual/devel/en/guix.html)
- [GNU Guix cookbook](https://guix.gnu.org/cookbook/en/guix-cookbook.html)
- See `references.md` for locating locally installed Info manuals and guidance on choosing the right reference.

For offline work, consult the converted local copies in `references/guix-manual.md` and `references/guix-cookbook.md`; `references.md` explains their provenance and how to locate installed Info manuals. These are snapshots, and the development manual may describe features newer than the pinned channel in this repository. Check `guix --version` and consult the matching local version when possible.

## Working in this repository

- Read `AGENTS.md` and `README.md`; read `PROGRESS.md` before any installation or recovery work.
- This is a personal M1 MacBook Air configuration booting from an initialized Samsung T7. Never rerun formatting or `guix system init` as a resume step.
- Prefer offline, non-mutating checks. Do not mount, activate, reconfigure, write bootloader/ESP data, or perform destructive operations without explicit approval. Preserve the NixOS ESP and recovery environment; never disable channel authentication.
- Keep `local/` private and ignored. Never commit firmware, credentials, password hashes, or machine backups. NixOS bootstrap and AWS infrastructure belong in `~/nixos`.
- Author Scheme in `modules/`; desktop assets belong in `desktop/`. `desktop/shared/` is independently maintained.
- Use jj, not git. Run `make test` for repository checks. Scheme evaluations require Guix and the pinned Asahi channel. Consult `BUILDING.md`, `INSTALLATION-PLAN.md`, and `desktop/README.md` when relevant.

## Practical workflow

1. Identify whether the task concerns packages, channels, system services, boot, or Guix Scheme; consult the matching manual/cookbook section and check local examples before editing.
2. Check the installed Guix version and the repository's channel/configuration context before assuming an API or command from the devel manual is available.
3. Make the smallest change, explain operational consequences, and run offline checks first. Never perform a system-changing command just to validate a config unless explicitly authorized.

# Guix on the Samsung T7

Personal Guix System configuration for an M1 MacBook Air. The T7 hosts the Guix root; a separate internal Asahi UEFI environment boots it. NixOS and macOS remain independent recovery systems. This repository owns the Guix configuration and `desktop/`; NixOS bootstrap/AWS work remains in `~/nixos`.

## Current state

- Guix boots from the T7 after adding `uas` to the initrd. The user reports both account passwords are set.
- The familiar desktop layer and patched xHCI/U-Boot handoff have been built and activated. The user reports successful desktop and hardware acceptance; this checkout does not independently capture the hardware-test logs.
- Preserve a known-good Guix generation and the independent NixOS/macOS recovery paths for future system changes.
- The SSD and internal Guix boot environment are already initialized. **Never rerun formatting or `guix system init` as a resume step.**

See [PROGRESS.md](PROGRESS.md) for the concise checkpoint, [INSTALLATION-PLAN.md](INSTALLATION-PLAN.md) for safety boundaries, [desktop/README.md](desktop/README.md) for desktop build/apply, and [BUILDING.md](BUILDING.md) for build notes. [REVIEW.md](REVIEW.md) records remaining review concerns.

## Development

```sh
make test
make eval
make eval-desktop
```

The Scheme checks require Guix and the pinned Asahi channel. `make build` builds the selected system configuration; `make apply` performs a guarded native reconfigure and requires a successful reviewed build. Desktop/Home-only changes can use `make home-build` and the interactive `make home-apply` workflow without reconfiguring the system or bootloader. See `make help`.

Author Scheme in `modules/` and desktop assets in `desktop/`. `desktop/shared/` is independently maintained and does not sync from NixOS. Keep `local/` private and ignored; never commit firmware, credentials, password hashes or machine backups. Use jj.

## Safety

Prefer offline checks. Mounting, activation, bootloader/ESP writes, formatting and other destructive operations require explicit approval. Preserve the protected NixOS ESP and recovery environment; never disable channel authentication. NixOS bootstrap changes belong in `~/nixos`, not here.

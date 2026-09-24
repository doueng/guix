# Guix on the internal drive

Personal Guix System configuration for an M1 MacBook Air. Guix boots from the internal Btrfs root and EFI system partition. macOS remains an independent recovery environment. This repository owns the Guix configuration and `desktop/`; NixOS bootstrap/AWS work remains in `~/nixos`.

## Current state

- Guix runs from the internal Btrfs root; its initrd includes `uas`. The user reports both account passwords are set.
- The familiar desktop layer and patched xHCI/U-Boot handoff have been built and activated. The user reports successful desktop and hardware acceptance; this checkout does not independently capture the hardware-test logs.
- Preserve a known-good Guix generation and independent recovery path for future system changes.
- The internal root and EFI system partition are initialized. **Never rerun formatting or `guix system init` as a resume step.**

See [PROGRESS.md](PROGRESS.md) for the current machine checkpoint, [INSTALLATION-PLAN.md](INSTALLATION-PLAN.md) for safety boundaries, [desktop/README.md](desktop/README.md) for desktop/Home operations, and [BUILDING.md](BUILDING.md) for historical build measurements and warning notes.

## Development

```sh
make test          # offline source and workflow checks
make eval          # installed system and Home, using pinned Guix/Asahi
```

`make eval-desktop` is an alias for `make eval`. Pinned Scheme checks require Guix and the pinned Asahi channel; they fail rather than silently skip when Guix is unavailable. `make build` builds without activating. `make switch` checks the native system/disk identities and runs one pinned reconfigure, which builds and activates without an extra confirmation prompt. `make apply` is an alias; no prior build or receipt is required. Desktop/Home-only changes can use `make home-build` and the interactive `make home-apply` workflow without reconfiguring the system or bootloader. Edits to already-linked files take effect immediately; new or removed files require a Home reconfigure to update links. See `make help`.

Author Scheme in `modules/` and desktop assets in `desktop/`. `desktop/shared/` is independently maintained and does not sync from NixOS. Keep `local/` private and ignored; never commit firmware, credentials, password hashes or machine backups. Use jj.

## Safety

Passwords remain managed outside the store with `passwd(1)`; reconfigure preserves them. The new desktop generation omits Sway/Foot; retain a previous Sway generation or a text console for recovery. Hardware behavior must be rechecked after relevant kernel, bootloader, desktop, storage or audio changes.

Prefer offline checks. Mounting, activation, bootloader/EFI writes, formatting and other destructive operations require explicit approval. Preserve the independent recovery environment; never disable channel authentication. NixOS bootstrap changes belong in `~/nixos`, not here.

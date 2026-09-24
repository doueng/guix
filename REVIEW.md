# Configuration review

The internal Guix installation is complete. The user reports that the system, desktop and hardware acceptance checks have passed; this checkout does not independently capture hardware-test logs.

## Retained operational notes

- Account passwords remain managed outside the store with `passwd(1)`; system reconfigure preserves them.
- `make switch` checks that the active system is Guix and that the internal root and EFI filesystem UUIDs match the configuration before reconfiguring. Reconfigure may update the EFI partition, so inspect the build and retain a known-good generation.
- The base Sway configuration remains available, but the familiar desktop removes Sway/Foot. Use a text console or a retained Sway generation for recovery.
- `desktop/shared/` is independently maintained and does not sync from NixOS. NixOS bootstrap/AWS changes belong in `~/nixos`.
- Private machine state belongs in ignored `local/`; never commit credentials, firmware or backups.

Run `make test`, `make eval` and `make eval-desktop` as appropriate. Hardware behavior must be rechecked after relevant kernel, bootloader, desktop, storage or audio changes.

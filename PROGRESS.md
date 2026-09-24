# Current checkpoint

## Installed system

- The native Guix system now runs from the internal Btrfs root (`c4f25409-b1a5-4ef0-8ac9-8e75f011668c`) and uses the internal EFI system partition (`5CDF-1DF4`). The T7-to-internal migration is complete; migration preparation and deployment tooling has been retired.
- The root and `engstrand` passwords are already set. Do not rerun initialization to change or verify them.
- The user reports that the desktop and hardware acceptance passed, including graphics/input/networking, lock/unlock, suspend/resume and protected audio routing/speaker safety. Hardware acceptance is user-reported, not independently reproduced here.
- The patched U-Boot/m1n1 bootloader and system configuration have been activated and verified booting, per user report. Keep a known-good system generation and independent recovery path available.

## Routine changes

- Continue routine changes from the native Guix session. `make switch` checks the native system and internal root/EFI filesystem UUIDs before reconfiguring. System reconfigure may update the EFI partition.
- Use `make home-build` / `make home-apply` for Home-only changes that do not affect system packages, services, kernel, bootloader or storage.
- Keep `local/` private and ignored. It may contain machine-specific backups and diagnostics; do not commit it.

## Validation

- Run `make test` for offline checks.
- `make eval` (also available as `make eval-desktop`) requires Guix and the pinned Asahi channel.
- Rebuild and repeat relevant hardware acceptance checks after changes to bootloader, kernel, desktop, storage or audio configuration.

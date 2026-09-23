# Current checkpoint

## Installed system

- Native Guix boots from the Samsung T7 after adding `uas` to the initrd. The fix is present in the authored configuration and covered by `tests/evaluate.scm`.
- The user reports setting both root and `engstrand` passwords. Do not rerun initialization to change or verify them.
- Do not format the T7, rerun `guix system init`, or overwrite the protected NixOS ESP.
- Existing root filesystem UUID: `c694c1fc-a241-459a-a60d-1c829f1b9c30`.
- Guix ESP UUID/PARTUUID: `77C4-10EC` / `5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc`.
- Protected NixOS ESP UUID: `5CDF-1DF4`. Verify live device identities before any privileged operation; partition numbers can change.

These identities document the installation, not authorization to mount, initialize, activate or write to it.

## Desktop and bootloader

- The familiar desktop configuration is maintained in `desktop/` and has been natively activated. The user reports that desktop and hardware acceptance passed, including graphics/input/networking, lock/unlock, USB-root suspend/resume, protected audio routing/speaker safety, and NixOS boot with the T7 disconnected.
- The patched U-Boot/m1n1 bootloader in `modules/engstrand/bootloader.scm` has been built, applied, and verified booting on hardware, per user report. The current `make switch` path builds `desktop/system.scm` and then runs guarded `guix system reconfigure`, which selects this bootloader via `make-ssd-os`.
- Acceptance status is user-reported; this checkout does not independently capture hardware-test logs. Preserve the working generation and independent recovery path for future updates.

## Next work

- Continue routine changes from the native Guix session. Before any future reconfigure, inspect the build and current disk identities; `make switch` changes the system generation and may update the Guix ESP.
- Keep the known-good generation and independent NixOS recovery path available. Re-run relevant acceptance checks after changes to the bootloader, kernel, desktop, storage, or audio configuration.

## Validation known to pass

- Python safety tests and the base/desktop Scheme evaluations passed during preparation; current `make test`, `make eval`, and `make eval-desktop` also pass.
- The UAS-corrected base system was built, initialized and booted.
- The user reports that the current desktop and patched bootloader were built/applied and passed the hardware acceptance checks described above.

Hardware acceptance is based on the user's report, not independently reproduced in this review. Rebuild and revalidate after relevant future changes. Ignored `local/` contains private snapshots and diagnostic artifacts; it is not portable source.

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

- The familiar desktop configuration is maintained in `desktop/` and builds from this checkout. Native desktop reconfigure/activation and desktop hardware testing remain pending.
- A patched U-Boot/m1n1 bootloader is authored in `modules/engstrand/bootloader.scm`. A self-contained snapshot was staged for native validation; the U-Boot source build and attached-at-boot verification remain outstanding. Do not infer that earlier system builds include this patch.
- Internal-speaker playback remains gated on verifying the machine-specific speaker protection and PipeWire/WirePlumber route. Suspend/resume on USB root, graphics/input and NixOS boot with the T7 disconnected also need acceptance.

## Next work

1. Resume only from the native Guix session with the T7 attached; verify live root/ESP identity and mounts before work. Do not reactivate the NixOS bootstrap merely to continue native acceptance.
2. Build and inspect the staged patched bootloader configuration using the pinned, authenticated channels. Preserve a working generation and back up the Guix ESP before any reviewed reconfigure.
3. If approved and the build succeeds, apply the desktop/bootloader changes through the guarded native workflow in [desktop/README.md](desktop/README.md). This is a system reconfigure and can update the Guix ESP; it is not a home-only operation.
4. Complete hardware and recovery checks before moving sensitive data or changing the startup default.

## Validation known to pass

- Python safety tests and the base/desktop Scheme evaluations passed during preparation.
- The UAS-corrected base system was built, initialized and booted.
- Desktop system builds succeeded before the latest patched-U-Boot validation step.

These results are not substitutes for rebuilding the current checkout, applying it, or completing hardware acceptance. Ignored `local/` contains private snapshots and diagnostic artifacts; it is not portable source.

# Installation safety and recovery

The internal Guix installation is complete. This file records operational boundaries; it is not an installation recipe. See [PROGRESS.md](PROGRESS.md) for the current checkpoint and [desktop/README.md](desktop/README.md) for the native reconfigure workflow.

## Operational boundaries

- Do not run `guix system init` again. It is an installation operation, not a reconfigure/update command.
- Preserve the existing Guix system generation and independent recovery environment.
- Never disable channel authentication to work around a fetch/build failure.
- Device names and partition numbers are not stable. Verify live filesystem identities, mounts and boot origin before any privileged operation.

Explicit approval is required before formatting, mounting for installation, activating host configuration, initializing a system, updating an EFI system partition/bootloader, changing Apple boot policy, or any other operation that risks data loss. Before an approved write, report the exact target filesystem UUID, partition UUID, parent disk identity and mount source; stop on mismatch or ambiguity.

A native `guix system reconfigure` updates the system and may update the EFI system partition. Use the guarded workflow in `desktop/README.md`; build successfully, inspect the result and retain a recovery generation before activation. Never use `guix system init` to apply desktop changes.

## Recovery and acceptance

Keep an independent recovery path available for system failures. Do not assume that preserving an EFI partition alone preserves a bootable operating system. Verify hardware behavior after changes to the bootloader, kernel, desktop, storage or audio configuration. Do not use internal speakers until Asahi speaker protection and the protected audio route are verified.

Private machine state and diagnostic artifacts belong in ignored `local/`; they are not portable source and must not be committed.

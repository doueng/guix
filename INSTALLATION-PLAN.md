# Installation safety and recovery

The original Guix-on-T7 installation is complete and boots. This file now records the operational boundaries; it is not a fresh-install recipe. See [PROGRESS.md](PROGRESS.md) for current status and [desktop/README.md](desktop/README.md) for the guarded native reconfigure workflow.

## Never repeat for routine work

- Do not format or repartition the T7.
- Do not run `guix system init` again. It is an installation operation, not a reconfigure/update command.
- Do not write to the protected NixOS ESP (`5CDF-1DF4`) or remove/replace the independent recovery environment.
- Do not change the Apple startup default or migrate sensitive data before acceptance.
- Never disable channel authentication to work around a fetch/build failure.

The T7 root UUID is `c694c1fc-a241-459a-a60d-1c829f1b9c30`. The Guix ESP is FAT UUID `77C4-10EC`, PARTUUID `5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc`. Device names and partition numbers are not stable; verify live identities, mounts and boot origin before privileged work. These recorded identities are not approval to write.

## Approval boundaries

Explicit approval is required before any operation that formats, mounts for installation, activates host configuration, initializes a system, updates an ESP/bootloader, changes Apple boot policy, or otherwise risks data loss. Before an approved write, report the exact target filesystem UUID, GPT PARTUUID, parent disk identity and mount source; stop on mismatch or ambiguity. Preserve independent backups and the working NixOS/Guix generations.

A native `guix system reconfigure` updates the Guix system and may update the Guix ESP. Use the guarded workflow in `desktop/README.md`; first build successfully, inspect the diff and target identity, and keep a recovery generation. Never use `guix system init` to apply desktop changes.

## Recovery and acceptance

- Keep the T7 connected while Guix is running from it. Finish builds and shut down normally before disconnecting it.
- If Guix fails, use Apple startup options to select the retained NixOS/recovery environment. Do not repair Guix by overwriting the NixOS ESP.
- Do not use internal speakers until the Asahi speaker-protection service and protected audio route are verified.
- Before treating the installation as accepted, test USB-root suspend/resume, graphics/input/networking, the desktop lock path, and NixOS boot with the T7 disconnected. Do not change the startup default or move sensitive data until these gates pass.

The former detailed installation proposal described a pre-install state and is intentionally retired: its partition inventory, bootstrap setup and installation command sequence are historical and must not be followed as current instructions.

# T7 configuration review

Reviewed during extraction from NixOS revision `07cf158e`. This is a source/offline review, not native hardware acceptance.

## Findings

1. **Boot reliability is not yet established.** `modules/engstrand/asahi.scm` includes `uas`, addressing the recorded post-enumeration failure. The separate xHCI handoff patch in `modules/engstrand/bootloader.scm` still needs a full build and attached-at-boot hardware verification according to the latest checkpoint. Do not treat the previous desktop build as validation of this new bootloader. Preserve the independent NixOS boot chain and back up the Guix ESP before an approved reconfigure.
2. **Fresh installs intentionally have an empty root password.** `make-ssd-os` declares root `""` and engstrand `"*"`. Existing passwords were reportedly set; this is not a reason to initialize again. A fresh installation still requires immediate password setup. The USB root is unencrypted: it is unsuitable for sensitive data without accepting physical-access exposure.
3. **Native reconfigure guards depended on shell state.** The desktop activation block lacked its own `set -e`, so UUID checks could fail without stopping when pasted into a fresh shell. Fixed by making that block independently fail-fast.
4. **Desktop recovery instructions overpromised Sway availability.** The desktop explicitly removes Sway/Foot. Fixed instructions to use a text console or the retained previous Sway generation, not assume a Sway session exists in the new generation.
5. **Staging was coupled to the NixOS tree.** `desktop.py` read Fish, Git/JJ, Herdr, Pi settings and editor assets from sibling features. Copied the exact inputs to `desktop/shared/` and changed staging to use them. Future changes in NixOS intentionally do not propagate automatically.
6. **Hardware/security acceptance remains open.** Verify native Hyprland compatibility, lock/unlock and lock-before-sleep, USB-root suspend/resume, speaker protection/routing and NixOS boot with the T7 disconnected. Do not use internal speakers until the recorded safety gate passes.

## Scope and retained limitations

- Disk helpers check UUID/PARTUUID, parent identity, boot origin and exact mounts. These are useful safeguards, not proof of ESP pairing or protection against hotplug races.
- `phase1-ssd.sh` is a historical destructive installer, not a resume helper. Its Python safety checks use assertions, which Python optimization can disable. Do not run it on the initialized T7.
- Snapshot manifests are integrity inventories, not signatures. `transfer-desktop.sh` checks copy equality but does not authenticate its source. Only transfer a reviewed local snapshot.
- The copied bootloader installer tracks private channel implementation; review it when updating channel pins. Channel revisions were not changed during extraction.
- Ignored `local/` artifacts were moved intact, not rewritten. Historical absolute paths in logs/snapshots may refer to the former location. Generate fresh snapshots for future changes rather than altering old manifests.
- NixOS bootstrap, AWS builder and the original migration assessment remain in `~/nixos`. The AWS Ghostty diagnostic accepts `--guix-repo`, defaulting to `~/guix`.

No formatting, mounts, system activation or ESP writes were performed as part of this review.

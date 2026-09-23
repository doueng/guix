# T7 configuration review

Reviewed during extraction from NixOS revision `07cf158e`. Hardware acceptance was subsequently reported as passing by the user; it was not independently reproduced in this source review.

## Findings

1. **Boot reliability: user-reported pass.** `modules/engstrand/asahi.scm` includes `uas`, addressing the recorded post-enumeration failure. The user reports building and applying the separate xHCI handoff patch in `modules/engstrand/bootloader.scm` and verifying boot with the T7 attached. Preserve the independent NixOS boot chain and a known-good generation for future changes.
2. **Fresh installs intentionally have an empty root password.** `make-ssd-os` declares root `""` and engstrand `"*"`. Existing passwords were reportedly set; this is not a reason to initialize again. A fresh installation still requires immediate password setup. The USB root is unencrypted: it is unsuitable for sensitive data without accepting physical-access exposure.
3. **Native reconfigure guards depended on shell state.** The desktop activation block lacked its own `set -e`, so UUID checks could fail without stopping when pasted into a fresh shell. Fixed by making that block independently fail-fast.
4. **Desktop recovery instructions overpromised Sway availability.** The desktop explicitly removes Sway/Foot. Fixed instructions to use a text console or the retained previous Sway generation, not assume a Sway session exists in the new generation.
5. **Staging was coupled to the NixOS tree.** The staging command read Fish, Git/JJ, Herdr, Pi settings and editor assets from sibling features. Copied the exact inputs to `desktop/shared/` and changed staging to use them. Future changes in NixOS intentionally do not propagate automatically.
6. **Desktop hardware acceptance: user-reported pass.** The user reports successful native desktop activation and verification of graphics/input/networking, lock/unlock, USB-root suspend/resume, speaker protection/routing, and NixOS boot with the T7 disconnected. This report closes the listed acceptance items; future relevant kernel, bootloader, desktop, storage, or audio changes should be retested.

## Scope and retained limitations

- Disk helpers check UUID/PARTUUID, parent identity, boot origin and exact mounts. These are useful safeguards, not proof of ESP pairing or protection against hotplug races.
- `phase1-ssd.sh` is a historical destructive installer, not a resume helper. Its Python safety checks use assertions, which Python optimization can disable. Do not run it on the initialized T7.
- The desktop configuration is evaluated directly from the fixed `~/guix` checkout. Keep that checkout readable by the Guix build/reconfigure process and review changes before applying them.
- The copied bootloader installer tracks private channel implementation; review it when updating channel pins. Channel revisions were not changed during extraction.
- Ignored `local/` artifacts were moved intact, not rewritten. Historical absolute paths in logs/snapshots may refer to the former location; they are not part of the direct desktop build.
- NixOS bootstrap, AWS builder and the original migration assessment remain in `~/nixos`. The AWS Ghostty diagnostic accepts `--guix-repo`, defaulting to `~/guix`.

No formatting, mounts, system activation or ESP writes were performed as part of this review.

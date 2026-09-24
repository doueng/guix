# Guix on the internal drive

Personal Guix System configuration for an M1 MacBook Air. Guix boots from the internal Btrfs root and EFI system partition. macOS remains an independent recovery environment. This repository owns the Guix configuration and `desktop/`; NixOS bootstrap/AWS work remains in `~/nixos`.

Layout: `desktop/system.scm` and `desktop/home.scm` are entry points; `modules/engstrand/desktop.scm` composes the system and Home; `modules/engstrand/system/` and `home/` group support code; `packages/` has one file per package plus shared inputs, and `services/` has one local service per file; `desktop/configs/` holds application configs and Herdr plugin manifests used at package build time; `desktop/bin/` and `desktop/keyd.conf` hold machine-specific assets. Herdr's `plugin-manifests/` are package build inputs, not Home links.

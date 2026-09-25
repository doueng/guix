# Guix on the internal drive

Personal Guix System configuration for an M1 MacBook Air. Guix boots from the internal Btrfs root and EFI system partition. macOS remains an independent recovery environment.

`desktop/system.scm` and `desktop/home.scm` are entry points; `modules/engstrand/config.scm` composes features in `modules/engstrand/features/`. Guix packages and services live under `modules/engstrand/`.

Mutable application configs live under `desktop/configs/<app>` and are linked directly into the corresponding `~/.config/<app>` directory using GNU Stow. `make home-apply` reconfigures Guix Home and refreshes these links; `make stow` refreshes only the mutable links. `make home-build` builds Home without activating it; `make switch` applies the Guix system.

Guix Home manages packages, services, environment variables and store-backed files. Build-time package assets are kept separately from `desktop/configs/`. See `desktop/README.md` for Stow targets and testing.

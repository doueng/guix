# Agent notes

Personal Guix configuration for an M1 MacBook Air; the installed system boots from the internal Btrfs root and EFI system partition.

- Keep `local/` private and ignored. Do not commit firmware, credentials, password hashes or machine backups.
- Author Scheme in `modules/` and mutable application configs in `desktop/configs/<app>`. `desktop/stow-home` links those configs into their live Home locations; keep build-time assets outside `desktop/configs/`.
- After changing the Doom config, run `make doom-check`. It fails when `doom doctor` reports a warning missing from `desktop/build/doom/doctor-baseline.txt`, such as an enabled module whose compiler or language server is not installed. Module changes in `init.el` or `packages.el` also need `doom sync` and an Emacs restart.
- Use jj, not git. Scheme evaluations require Guix and the pinned Asahi channel. See `README.md` for workflows.
- Pi config lives in `desktop/pi/agent/`. Keep MCP servers on the built-in integration with `exposure: "codemode"`, preserve deliberate `hidden` tools, and avoid `direct` or `deferred` overrides. Keep credentials and private server configuration out of the repository.
- Check desktop behavior yourself before asking the user to try it. `ydotool key 125:1 23:1 23:0 125:0` presses Cmd+I upstream of keyd, so keyd layers apply; codes are Linux `KEY_*` values. `hyprctl -j clients` and `hyprctl -j activewindow` show what opened, and `grim /tmp/shot.png` captures the screen. `timeout 10 sudo -n keyd monitor` shows the keys keyd emits, and `sudo -n keyd reload` applies `/etc/keyd` after a switch. `make switch` still needs the user because it is root-equivalent; see `modules/engstrand/features/agent-control.scm`.
- Pi discovers the project Guix skill at `.agents/skills/guix/SKILL.md`; use it for Guix-specific tasks and its `references.md` for local/manual links.

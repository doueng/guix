# Agent notes

Personal Guix configuration for an M1 MacBook Air (Asahi) that boots from the internal Btrfs root.

- Keep `local/` private and ignored. Never commit firmware, credentials, password hashes or machine backups.
- Scheme lives in `modules/`. Mutable app configs live in `desktop/configs/<app>`, which `desktop/stow-home` links into Home. Keep build-time assets out of `desktop/configs/`.
- Write scripts in babashka (`#!/usr/bin/env bb`). `make lint` rejects shell scripts.
- Use jj, not git. Scheme evaluation needs Guix and the pinned Asahi channel. `make help` lists every workflow.
- Checks. `make lint` takes seconds and also checks that the Makefile, this file and `desktop/build/checks/checks.edn` agree. `make affected` lists the checks covering files changed in `@` (`REV=@-` for the last commit). `make verify` runs lint and those checks, and `SLOW=1` adds builds. A new component or test needs a `checks.edn` rule, and a new Makefile target needs a `make help` line.
- Doom. After config changes run `make doom-check`, which fails on `doom doctor` warnings missing from `desktop/build/doom/doctor-baseline.txt`. Changes to `init.el` or `packages.el` also need `doom sync` and an Emacs restart.
- Pi config lives in `desktop/pi/agent/`. Keep MCP servers on the built-in integration with `exposure: "codemode"`, keep deliberate `hidden` tools, avoid `direct` or `deferred` overrides, and keep credentials and private server config out of the repo.
- Test desktop behavior yourself before asking the user. `ydotool key 125:1 23:1 23:0 125:0` presses Cmd+I upstream of kanata (Linux `KEY_*` codes). `hyprctl -j clients`, `hyprctl -j activewindow` and `grim /tmp/shot.png` show the result. `make kanata-check` simulates a chord, `hyprkan layer` prints the active layer, and `hyprkan reload` applies `/etc/kanata/kanata.kbd`. `make switch` is root-equivalent and needs the user; see `modules/engstrand/features/agent-control.scm`.

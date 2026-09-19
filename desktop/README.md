# Familiar desktop layer

Opt-in native Guix configuration for the existing SSD installation. This is a first usability port, not complete NixOS parity. The minimal Sway installation remains the default constructor and recovery session. No Nix daemon, systemd/UWSM, partition changes or channel updates are introduced.

## Included

- Hyprland with the current monitor scale, gaps, rounded windows, touchpad settings and workspace/window controls. Sway and Foot are removed; SDDM starts the Hyprland session.
- **Pi** (`pi-coding-agent`), **Herdr** and **jjui** (official aarch64 release binaries), with the shared Pi prompts, skills, keybindings and local extensions, plus the shared Herdr configuration and `herdr-tab-focus`, `herdr-workspace-pick` and `herdr-agent-pick` helpers. Pi's `settings.json` points `shellPath` at the Guix system Bash. These private packages build locally in seconds; everything else comes from substitutes. The npm-based Pi packages and Herdr plugins from NixOS are not preinstalled; install them on first use with `pi install …`.
- The existing keyd Caps/Escape/Control, Shift/parenthesis and Command layers, managed by Shepherd with `uinput` loaded first. `sudo herd stop keyd` temporarily restores raw keyboard input if a mapping causes trouble.
- Kitty with Fish, JetBrains Mono and Catppuccin Mocha colors; Noctalia 5.1.0 as the Hyprland shell/launcher (with Waybar and Wofi retained as fallbacks); `nmtui` network controls.
- The shared Fish prompt, vi bindings, aliases and functions. A Guix-specific PATH file preserves inherited paths and adds Guix profiles and `/run/privileged/bin`. The Nix/UWSM login files and supervisor autostart are not copied.
- Shared Git/JJ configuration, with Watchman disabled because it is unavailable in the evaluated Guix runtime. Jujutsu, jjui, the GitHub CLI (`github-cli`, `gh`), difftastic, tmux, fzf, zoxide, direnv, ripgrep, fd, jq, bat, btop, curl, Python, Node and basic build tools.
- **Chromium via Flatpak**: the `flatpak` package (fully substitutable) behind the ported `chrome-unified` launcher with the shared Wayland flags. Guix's `ungoogled-chromium` has no aarch64 substitute and would need a multi-hour source build. Install the browser once after first boot (commands below). Old Reddit extension loading looks under `~/.config/chromium/extensions/`.
- All user-facing custom launchers and helpers are installed as editable Home links: the Noctalia/Wofi **custom launcher**, Chromium launcher, and every script under the shared Herdr `bin/` directory. Pi's complete `agent/` tree, including its shell hooks and scripts, is linked as well.
- Neovim with the full mutable Fennel configuration, LazyVim/plugin declarations, Pi-org integration, Wayland clipboard and the existing options/keymaps. The config is linked from this checkout; install or update its runtime plugins separately when first launching it.
- Terminal-only Doom Emacs using Guix's `emacs-no-x`, with the migrated Doom modules, keybindings, Clojure support and config linked from this checkout. The Doom framework itself is kept in `~/.config/emacs` and must be bootstrapped once with the commands below.
- Hyprlock PAM integration (empty passwords rejected), a manual lock binding and a ten-minute idle lock. No automatic suspend is added. Test unlocking and lock-before-sleep on this machine before trusting it.

The Asahi kernel is inherited with the uinput module enabled for keyd; initrd/UAS, filesystem UUIDs, bootloader definition, account declarations, channel pins and audio/D-Bus services remain preserved. Normal reconfiguration still updates the Guix generation and its boot menu/bootloader on the new ESP; it is not a home-only operation.

Internal speakers remain unverified. There are deliberately no volume/unmute bindings or audio widgets, and Kitty's audio bell is disabled. Do not enable playback until the existing speaker-safety gate passes.

## Deliberate gaps

**Ghostty remains unavailable on Guix.** A from-source Ghostty packaging was attempted and abandoned: it needs Zig 0.15.2 (available), 36 vendored dependencies (staged), and repeatedly broke on sandbox/pkg-config integration. Noctalia 5.1.0 is now packaged from its upstream release tarball with the required Wayland/Qt-adjacent libraries; its package build has passed on native aarch64. Kitty remains the terminal, while Waybar and Wofi remain available as fallbacks. The Ghostty package draft remains in `modules/engstrand/packages.scm` for a future attempt.

Neovim's Guix runtime plugin dependencies (LazyVim, Java tooling and unavailable language servers), Herdr's plugin set (annotate, tiny-fingers, sesh) and Tailscale are not ported yet. Babashka is included as `bb`, so `.bb` scripts can run directly; the NixOS-only `nixdiag` service script remains with NixOS because it depends on systemd/journald/NixOS paths. The copied Herdr tab helpers now call `herdr` directly; the keyd Command layer and Ghostty chord binds have Alt/Super equivalents in Hyprland.

Useful controls with keyd active:

| Input | Action |
| --- | --- |
| Cmd+Return / Cmd+D | Terminal |
| Cmd+Space | Custom launcher (Noctalia dmenu or Wofi) |
| Cmd+E | Editor terminal |
| Cmd+G | Terminal showing `jj status` |
| Cmd+S | Herdr |
| Cmd+U / Cmd+Shift+U | Workspaces 2 / 3 |
| Alt+1…0 / Alt+Shift+1…0 | Select / move window to workspace |
| Alt+W / Alt+F | Close / fullscreen |
| Ctrl+Alt+L | Lock |
| Ctrl+Shift+T / Ctrl+Tab in Kitty | New / next Kitty tab |
| Brightness keys | Brightness |
| Ctrl+Shift+4 / Print | Region to clipboard / screenshot to Pictures |

The compositor handles both Super bindings and the Alt sequences emitted by the existing keyd Command layer. Clipboard bindings also handle keyd's Ctrl+Insert/Shift+Insert output. Herdr-specific tab focusing is not emulated; run `herdr` and use its own keys.

Neovim: Space+ff selects files, Space+bb lists buffers, Space+e opens netrw, Space+/ starts an `rg` search, and Alt+comma/period selects buffers. These are simpler fallbacks, not the existing plugin pickers.

## Apply from a fresh clone

On the running Guix system, the `Makefile` wraps this flow: `make stage` (snapshot from `BASE`, default `/etc/guix-ssd`), `make build`, then `make apply` for the guarded reconfigure below. `make help` lists everything. It never formats, mounts, or runs `guix system init`.

## Stage without modifying either OS

From the root of this repository (on NixOS or Guix):

```sh
KIT="$PWD"
CFG="$KIT/local/familiar-$(date +%Y%m%d-%H%M%S)"
python3 "$KIT/desktop.py" --base "$KIT/local/install" --output "$CFG"
tar -C "$CFG" -czf "$CFG.tar.gz" .
```

`desktop.py` only writes a new local snapshot. It refuses existing output, malformed UUIDs, the protected NixOS ESP and channel drift. `devices.json` is carried forward, not regenerated or treated as fresh approval. `sources.json` records hashes of the staged files. The generated Home configuration uses out-of-store symlink targets back to the checkout that ran `desktop.py` (normally `~/guix`), so Fish, Doom, Neovim, Pi and the desktop assets can be edited without rebuilding Guix. Keep that checkout at the same path; do not edit the generated snapshot's copied files.

For a fresh clone, run staging and building from that clone on the Guix system so its live-link root is correct. An archive made on another machine is still useful for review, but its live links intentionally point to the source checkout that created it and are not self-contained.

## Build in native Guix, then review activation

Run in Bash in Guix, with the archive unpacked at `~/guix-desktop`:

```sh
set -euo pipefail
CFG="$HOME/guix-desktop"
guix time-machine -C "$CFG/channels.scm" -- \
  system build -L "$CFG/modules" "$CFG/system.scm"
```

Stop on failure. This full pinned build must succeed before activation; source evaluation alone is insufficient. Save your current `~/.config` privately outside the staged bundle, retain `/etc/guix-ssd` and the working system generation, and review any Guix Home conflicts rather than forcing them away.

After build success and approval to apply the desktop, verify this installation's native destinations immediately before reconfiguration:

Bootstrap Doom Emacs once before first use, if `~/.config/emacs` does not exist:

```sh
set -euo pipefail
DOOM="$HOME/.config/emacs"
test ! -e "$DOOM"
git clone --depth 1 https://github.com/doomemacs/doomemacs "$DOOM"
"$DOOM/bin/doom" install --no-env --no-fonts
```

The `~/.config/doom` directory is the live Guix checkout link. Doom's own framework and downloaded packages remain in `~/.config/emacs` and `~/.emacs.d/.local`; do not replace the checkout link with the framework directory.

```sh
set -euo pipefail
case "$(readlink -f /run/current-system)" in /gnu/store/*) ;; *) exit 1 ;; esac
test "$(findmnt -nro UUID /)" = c694c1fc-a241-459a-a60d-1c829f1b9c30
test "$(findmnt -nro UUID /boot/efi)" = 77C4-10EC
test "$(tr -d '\0' < /proc/device-tree/chosen/asahi,efi-system-partition)" = \
  5408cbd2-dc6c-49c6-bee9-c51c5f3a29fc

DEST="/etc/guix-ssd-desktop-$(date +%Y%m%d-%H%M%S)"
sudo test ! -e "$DEST"
sudo cp -a "$CFG" "$DEST"
sudo chown -R root:root "$DEST"
sudo chmod -R a+rX "$DEST"
sudo guix time-machine -C "$DEST/channels.scm" -- \
  system reconfigure -L "$DEST/modules" "$DEST/system.scm"
```

These checks are for the already-running native system, not the NixOS installer mount gate. Review the retained UUID/PARTUUID/disk record too. Never use `guix system init` or `rofl.sh` to apply this layer. Existing passwords are not an onboarding step again; do not run `passwd` unless you intend to change them.

After first boot into the new desktop, install the browser once:

```sh
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install flathub org.chromium.Chromium
```

Log out and select Hyprland in SDDM. Retain the previous Sway generation in GRUB; Sway is removed from the new desktop generation. Check `sudo herd status keyd`, the lock/unlock path, terminal/editor startup, brightness, Wi-Fi, and audio protection before further hardware tests. If the desktop fails, use a text console or boot the previous Sway generation; retain the old Guix generation in GRUB. `sudo guix system roll-back` is an explicit recovery operation, not something the staging script runs, and does not restore every mutable Home setting or Apple boot artifact.

## Checks

```sh
python3 -m unittest discover -s "$KIT/tests" -v
guix time-machine -C "$CFG/channels.scm" -- repl \
  -L "$CFG/modules" \
  "$KIT/tests/evaluate-desktop.scm" "$CFG/channels.scm"
Hyprland --verify-config -c "$CFG/modules/engstrand/desktop-files/.config/hypr/hyprland.lua"
```

The Python suite checks staging safety, portable shell/JJ settings and Fish syntax/PATH. The pinned Scheme test checks both service graphs and preservation of the working system's storage/kernel/accounts/audio/pins. The full pinned system build remains the meaningful validation for Neovim and Doom package/runtime integration. A successful parser check does not establish graphics, keyd, locking or suspend behavior.

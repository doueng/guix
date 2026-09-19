# Familiar desktop layer

Opt-in native Guix configuration for the existing SSD installation. This is a first usability port, not complete NixOS parity. The minimal Sway installation remains the default constructor and recovery session. No Nix daemon, systemd/UWSM, partition changes or channel updates are introduced.

## Included

- Hyprland with the current monitor scale, gaps, rounded windows, touchpad settings and workspace/window controls. Sway and Foot are removed; SDDM starts the Hyprland session.
- **Pi** (`pi-coding-agent`), **Herdr** and **jjui** (official aarch64 release binaries), with the shared Herdr configuration and the `herdr-tab-focus`, `herdr-workspace-pick` and `herdr-agent-pick` helpers. Pi's `settings.json` points `shellPath` at the Guix system Bash. These private packages build locally in seconds; everything else comes from substitutes. The npm-based Pi packages, extensions and Herdr plugins from NixOS are not preinstalled; install them on first use with `pi install …`.
- The existing keyd Caps/Escape/Control, Shift/parenthesis and Command layers, managed by Shepherd with `uinput` loaded first. `sudo herd stop keyd` temporarily restores raw keyboard input if a mapping causes trouble.
- Kitty with Fish, JetBrains Mono and Catppuccin Mocha colors; Waybar with workspaces, network, battery and clock; Wofi launcher; `nmtui` network controls.
- The shared Fish prompt, vi bindings, aliases and functions. A Guix-specific PATH file preserves inherited paths and adds Guix profiles and `/run/privileged/bin`. The Nix/UWSM login files and supervisor autostart are not copied.
- Shared Git/JJ configuration, with Watchman disabled because it is unavailable in the evaluated Guix runtime. Jujutsu, jjui, the GitHub CLI (`github-cli`, `gh`), difftastic, tmux, fzf, zoxide, direnv, ripgrep, fd, jq, bat, btop, curl, Python, Node and basic build tools.
- **Chromium via Flatpak**: the `flatpak` package (fully substitutable) behind the ported `chrome-unified` launcher with the shared Wayland flags. Guix's `ungoogled-chromium` has no aarch64 substitute and would need a multi-hour source build. Install the browser once after first boot (commands below). Old Reddit extension loading looks under `~/.config/chromium/extensions/`.
- The ported **custom launcher**: Noctalia `dmenu` when Noctalia is present, otherwise Wofi; entries for the terminal, Chromium (normal/incognito), Herdr and Pi.
- Neovim with the existing core Fennel options/keymaps compiled by Guix, Wayland clipboard, matching colors and plugin-free file/buffer/grep fallbacks. It starts without network access or plugin downloads.
- Hyprlock PAM integration (empty passwords rejected), a manual lock binding and a ten-minute idle lock. No automatic suspend is added. Test unlocking and lock-before-sleep on this machine before trusting it.

The Asahi kernel is inherited with the uinput module enabled for keyd; initrd/UAS, filesystem UUIDs, bootloader definition, account declarations, channel pins and audio/D-Bus services remain preserved. Normal reconfiguration still updates the Guix generation and its boot menu/bootloader on the new ESP; it is not a home-only operation.

Internal speakers remain unverified. There are deliberately no volume/unmute bindings or audio widgets, and Kitty's audio bell is disabled. Do not enable playback until the existing speaker-safety gate passes.

## Deliberate gaps

**Ghostty and Noctalia are not installable on Guix today.** Neither exists in Guix's package collection. A from-source Ghostty packaging was attempted and abandoned: it needs Zig 0.15.2 (available), 36 vendored dependencies (staged), but repeatedly broke on sandbox/pkg-config integration; Noctalia needs a Quickshell package first. Kitty is the terminal; Waybar the bar; the launcher falls back from Noctalia to Wofi. The Ghostty package draft remains in `modules/engstrand/packages.scm` for a future attempt.

The full LazyVim/FFF/Java/LSP Neovim setup, Pi's extension tree (jj-guard, tokenjuice, vent, autoresearch), Herdr's plugin set (annotate, tiny-fingers, sesh), Babashka-based repo scripts and Tailscale are also not ported yet. The copied Herdr tab helpers now call `herdr` directly; the keyd Command layer and Ghostty chord binds have Alt/Super equivalents in Hyprland.

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

`desktop.py` only writes a new local snapshot. It refuses existing output, malformed UUIDs, the protected NixOS ESP and channel drift. `devices.json` is carried forward, not regenerated or treated as fresh approval. `sources.json` records hashes of the staged files. Shared assets are copied from this repository's `desktop/shared/` snapshot; edits do not affect the already-installed desktop until a new snapshot is built and applied. Do not edit Guix Home's store-backed links in place.

Transfer the archive to Guix using a reviewed destination or independent medium, and unpack it into a fresh directory there. Do not blindly mount/write an SSD that has not been re-identified. The complete archive works without this checkout, Nix or the librarian caches.

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
ASAHI="$HOME/.cache/checkouts/codeberg.org/asahi-guix/channel"
guix repl \
  -L "$ASAHI/modules" -L "$CFG/modules" \
  "$KIT/tests/evaluate-desktop.scm" "$CFG/channels.scm"
Hyprland --verify-config -c "$CFG/modules/engstrand/desktop-files/.config/hypr/hyprland.lua"
```

The source checks use the available Nix-packaged Guix runtime and pinned Asahi source, not a fully realized pinned time-machine. The Python suite checks staging safety, portable shell/JJ settings and, when host tools exist, Fish syntax/PATH and isolated Neovim startup. The Scheme test checks both service graphs and preservation of the working system's storage/kernel/accounts/audio/pins. Initial Hyprland parsing was checked with NixOS's 0.56.2; the available Guix package is 0.55.4, so its build and native parser/session still need testing. A successful parser check does not establish graphics, keyd, locking or suspend behavior.

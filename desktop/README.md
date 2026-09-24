# Familiar desktop layer

Native Guix desktop for the internal-drive installation. The familiar desktop is activated, though it does not aim for complete NixOS parity. The inherited Asahi Sway configuration remains the base for system services; retain a previous Sway generation or text console for recovery. No Nix daemon, systemd/UWSM, partition changes or channel updates are introduced. See [PROGRESS.md](../PROGRESS.md) for current status.

## Included

- Hyprland with the current monitor scale, gaps, rounded windows, touchpad settings and workspace/window controls. Sway and Foot are removed; SDDM starts the Hyprland session.
- **Pi** (`pi-coding-agent`), **Herdr** and **jjui** (official aarch64 release binaries), with the shared Pi prompts, skills, keybindings and local extensions, plus the shared Herdr configuration and `herdr-tab-focus`, `herdr-workspace-pick` and `herdr-agent-pick` helpers. Pi's `settings.json` points `shellPath` at the Guix system Bash. These private packages build locally in seconds; everything else comes from substitutes. Herdr's Sesh and Tiny Fingers plugins are packaged, linked and enabled during Home activation.
- The existing keyd Caps/Escape/Control, Shift/parenthesis and Command layers, managed by Shepherd with `uinput` loaded first. `sudo herd stop keyd` temporarily restores raw keyboard input if a mapping causes trouble.
- Ghostty (the default terminal) and Kitty with Fish, JetBrains Mono and Catppuccin Mocha colors; Noctalia 5.1.0 as the Hyprland shell/launcher (with Wofi retained as a launcher fallback); `nmtui` network controls.
- The shared Fish prompt, vi bindings, aliases and functions. A Guix-specific PATH file preserves inherited paths and adds Guix profiles and `/run/privileged/bin`. The Nix/UWSM login files and supervisor autostart are not copied.
- Shared Git/JJ configuration, with Watchman disabled because it is unavailable in the evaluated Guix runtime. Jujutsu, jjui, the GitHub CLI (`github-cli`, `gh`), difftastic, tmux, fzf, zoxide, direnv, ripgrep, fd, jq, bat, btop, curl, Python, Node and basic build tools.
- Home sets MIME defaults for HTTP/HTML to Flatpak Chrome and directories to Thunar; text files stay with the user's current choice. The browser defaults need Chrome installed and its exported desktop entry visible in the graphical session.
- **Google Chrome via Flatpak**: the `flatpak` package (fully substitutable) behind the ported `chrome-unified` launcher with shared Wayland flags. Guix's `ungoogled-chromium` has no aarch64 substitute and would need a multi-hour source build. Install Chrome once after first boot (commands below); Flatpak manages its updates independently of Guix generations. Home adds Flatpak's user/system desktop-entry exports to `XDG_DATA_DIRS`; check them in the graphical session after a Home apply. Old Reddit extension loading looks under `~/.var/app/com.google.Chrome/config/chromium/extensions/`.
- All user-facing custom launchers and helpers are installed as editable Home links: the Noctalia/Wofi **custom launcher**, Chromium launcher, and every script under the shared Herdr `bin/` directory. Pi's complete `agent/` tree, including its shell hooks and scripts, is linked as well.
- Neovim with the full mutable Fennel configuration, LazyVim/plugin declarations, Pi-org integration, Wayland clipboard and the existing options/keymaps. A Guix-owned Lua bootstrap installs lazy.nvim under `~/.local/share/nvim/lazy` on first launch; the remaining plugins are then managed by lazy.nvim. The config is linked from this checkout.
- Terminal-only Doom Emacs using the editable `emacs` launcher (which selects `~/.config/emacs`), with the migrated Doom modules, keybindings, Clojure support and config linked from this checkout. Parinfer's native Rust library is provided by Guix's aarch64-capable `parinfer-rust-emacs` package, and Doom is configured to use Indent Mode. The Doom framework itself is kept in `~/.config/emacs` and must be bootstrapped once with the commands below.
- Hyprlock PAM integration (empty passwords rejected) and a manual lock binding. Automatic idle and suspend locking are disabled; use Ctrl+Alt+L when you want to lock.

The Asahi kernel is inherited with the uinput module enabled for keyd; initrd/UAS, filesystem UUIDs, bootloader definition, account declarations, channel pins and audio/D-Bus services remain preserved. A system reconfigure updates the Guix generation and may update its boot menu/bootloader on the new ESP. For Home-only changes, use the standalone `desktop/home.scm` config and `make home-build` / `make home-apply`; this updates the user's Home generation without changing the system generation or ESP.

Volume keys control the protected PipeWire default sink through `wpctl`; they do not bypass Asahi speaker safety. The user reports that speaker protection and routing have passed hardware verification. Ghostty's audio bell remains disabled.

## Deliberate gaps

This is not full NixOS parity. The user reports that native activation and hardware acceptance passed; package availability/build success alone does not establish hardware behavior. Automatic idle/suspend locking is intentionally disabled. Neovim Java tooling, some language servers, Herdr's annotate plugin and Tailscale are not ported. LazyVim and its runtime plugins are fetched by lazy.nvim on first launch. Babashka is available as `bb`; the NixOS-only `nixdiag` script remains with NixOS because it depends on systemd/journald/NixOS paths. The Herdr tab helpers call `herdr` directly, and terminal bindings focus the existing Ghostty window/select its tab when appropriate.

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
| Ctrl+Shift+T / Ctrl+Tab in Ghostty | New / next Ghostty tab |
| Volume keys / mute | Protected PipeWire default sink |
| Mic-mute key | Five-second system information notification |
| Brightness keys | Brightness |
| Ctrl+Shift+4 / Print | Region to clipboard / screenshot to Pictures |

The compositor handles both Super bindings and the Alt sequences emitted by the existing keyd Command layer. Clipboard bindings also handle keyd's Ctrl+Insert/Shift+Insert output. Terminal bindings focus the existing Ghostty window or select the requested Herdr tab instead of opening another terminal.

Neovim: Space+ff selects files, Space+bb lists buffers, Space+e opens netrw, Space+/ starts an `rg` search, and Alt+comma/period selects buffers. These are simpler fallbacks, not the existing plugin pickers.

## Build and apply directly from `~/guix`

The repository is the live source of the desktop configuration. `desktop/system.scm` declares the internal Btrfs root and EFI system partition UUIDs directly and loads the pinned `channels.scm` from this checkout. It references Fish, Neovim, Doom, Pi and desktop assets directly; no snapshot or archive is created.

From the repository root on the native Guix system, build and activate the configuration:

```sh
make switch
```

This explicitly requests system activation and Guix ESP updates, with no extra
confirmation beyond sudo. Guix builds successfully before activating. For an
optional non-activating preview, use `make build` or `make dry-run` first.

For editor, shell, or Home package changes that do not alter system packages/services, build and interactively activate the standalone Home configuration instead:

```sh
make home-build
make home-apply
```

`desktop/home.scm` evaluates the same `%familiar-home` object embedded by the system config, so the Home package and service definitions stay in one place. Home activation is per-user and does not use `sudo`, reconfigure Guix System, or write the ESP. Edits to already-linked files are live; adding or removing a file requires Home reconfigure to refresh the links. Changes to OS packages, kernel, services, bootloader or storage still require the reviewed system build/apply workflow.

`make switch` checks that it is running on Guix and that the mounted root and
EFI partition match this installation before invoking the pinned
`guix system reconfigure`. This changes the active system generation and may
update the EFI system partition; inspect the build and retain a recovery
system generation before applying.

The authenticated pinned Guix is resolved
as your user before sudo, avoiding root's separate time-machine trust/cache.
There are no receipts, fingerprints or reuse options. `make apply` is a
compatibility alias for the same operation. Switch skips optional kexec loading
by default; normal reboot and ESP updates are unchanged. Use
`make switch RECONFIGURE_FLAGS=` only if you want Guix to prepare fast kexec
reboot as well.

For measured build-speed options and the two known upstream/trust warnings,
see [BUILDING.md](../BUILDING.md).

`make build` performs the full pinned build without activation. Stop on failure; source evaluation alone is insufficient. Keep the repository at `~/guix`, save your current `~/.config` privately, retain the working system generation, and review any Guix Home conflicts rather than forcing them away.

Bootstrap Doom Emacs once before first use, if `~/.config/emacs` does not exist. The Guix `emacs` command already selects this directory:

```sh
set -euo pipefail
DOOM="$HOME/.config/emacs"
test ! -e "$DOOM"
git clone --depth 1 https://github.com/doomemacs/doomemacs "$DOOM"
"$DOOM/bin/doom" install --no-env
"$DOOM/bin/doom" sync
```

The `~/.config/doom` directory is the live Guix checkout link. Doom's own framework remains in `~/.config/emacs` and downloaded packages in Doom's data directory; do not replace the checkout link with the framework directory.

Run `make switch` (or its `make apply` alias) only from the internal Guix
session. The target checks the root and ESP filesystem UUIDs before
reconfiguring. Never use `guix system init` to apply this layer. Existing
passwords are not an onboarding step again; do not run `passwd` unless you
intend to change them.

After the desktop configuration is applied, install Google Chrome once through Flatpak:

```sh
flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak --user install flathub com.google.Chrome
```

Log out and select Hyprland in SDDM. Retain the previous Sway generation in GRUB; Sway is removed from the new desktop generation. Check `sudo herd status keyd`, the lock/unlock path, terminal/editor startup, brightness, Wi-Fi, and audio protection before further hardware tests. If the desktop fails, use a text console or boot the previous Sway generation; retain the old Guix generation in GRUB. `sudo guix system roll-back` is an explicit recovery operation and does not restore every mutable Home setting or Apple boot artifact.

## Checks

```sh
KIT="$HOME/guix"
guix time-machine -C "$KIT/channels.scm" -- repl \
  -L "$KIT/modules" \
  "$KIT/tests/evaluate-desktop.scm" "$KIT/channels.scm"
Hyprland --verify-config -c "$KIT/desktop/home/.config/hypr/hyprland.conf"
```

The direct Scheme source check verifies managed Home sources in the checkout (wallpapers are optional). `make eval` runs the pinned Scheme test of the installed configuration, including the service/Home graphs and storage/kernel/accounts/audio/pins. The full pinned system build remains the meaningful validation for Neovim and Doom package/runtime integration. A successful parser check does not establish graphics, keyd, locking or suspend behavior.

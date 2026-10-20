# Install and maintain the bindings

## Apply desktop changes

1. Run `bb desktop/stow-home` from the repository root.
2. Run `hyprctl reload`.
3. Run `hyprctl configerrors` and confirm that the output is empty.
4. Reload Ghostty's configuration with Ctrl+Alt+Shift+R.
5. Reload Herdr with Ctrl+b Shift+R.
6. Restart Neovim after installing missing plugins with `:Lazy install`.
7. Reload Doom with Space h r r, or restart Emacs.

8. Start the which-key panel listener once with `setsid -f ~/.local/bin/keyleader listen`. Hyprland starts it at later logins. A second copy exits immediately.
9. In Neovim, run `:Lazy install` for smart-splits and its Herdr backend.

No keyd change or system switch is required for these additions.

## Add or change a leader sequence

1. Edit `desktop/configs/keybindings/leader.edn`. Give the entry an action for each context that should have it.
2. Run `bb desktop/build/keybindings/generate.bb` from the repository root.
3. Run `make keybindings-check`.
4. Run `bb desktop/stow-home` if the change added a file, then `hyprctl reload` and `herdr server reload-config`.
5. Restart Neovim, reload Doom with Space h r r, and reload IdeaVim with `:source ~/.ideavimrc` in IntelliJ.

Herdr's popup, the desktop hints, and the desktop palette read `leader.edn` at run time.
Hyprland only runs the keys compiled into `hypr/leader.lua`. Until you regenerate and reload, the palette can list a sequence that Hyprland does not bind.
Run `keyleader --verify-hypr` after `hyprctl reload`. It fails when Hyprland's loaded leader binds differ from `leader.edn`.

## Add a direct desktop shortcut

1. Edit `desktop/configs/keybindings/bindings.edn`.
2. Run `bb desktop/build/keybindings/generate.bb` from the repository root. It rewrites `hypr/keybindings.lua`.

3. Run `make keybindings-check`.
4. Run `hyprctl reload`.

Use `keyguide --list` for a terminal reference.
The existing inventory command also includes native application bindings.

## Add Vimium aliases without replacing settings

The supplied `blob:chrome-extension://...` URL is a temporary browser object, not a portable configuration file.
The repository does not contain its settings. Existing exclusions, search engines, and custom mappings must remain private.

1. Open Cmd+/ and choose Chrome and Vimium, then Vimium options.
2. Download a backup from Vimium's options page before editing.
3. Read `desktop/configs/vimium/keybindings.txt`.
4. Append its mappings to the Custom key mappings field, resolving any existing bindings for the same sequences first.
5. Save changes.
6. On a normal web page, press `g?` and confirm that the help opens.

The aliases add `gb` for tab search, `g/` for page search, `gh` for back, and `gl` for forward.
All Vimium defaults remain available. No `unmapAll` is used.
Use the extension's site exclusions for web IDEs and applications that need their own plain-letter shortcuts.
Vimium cannot run on Chrome internal pages or the Chrome Web Store.

## Run verification

Run `make keybindings-check` for the registry, Doom coach tests, and Neovim motion tests.
The Neovim check uses the installed user configuration and needs its plugins installed.
Run `make doom-check` after Doom changes.
Run `ghostty +validate-config` after Ghostty changes.
Run `bb desktop/build/keybindings/verify.bb` to exercise Cmd+/, Escape, and Ctrl+Alt+0 through keyd on the live desktop.
That check temporarily changes workspaces, opens the Herdr menu with Cmd+Space, and restores the original workspace.
Run `make hypr-nested-check` to load `hypr/hyprland.lua` in a nested Hyprland and type the leader, Ctrl+Alt and Cmd+Space keys into it with wtype. The nested window stays on a hidden special workspace, so it does not take focus.

`desktop/build/keybindings/decisions.tsv` records this implementation's decisions and verification gaps.

# Keyboard reference

## Ownership

| Context | Family | Owner |
| --- | --- | --- |
| Physical keyboard | Caps held for Ctrl, Caps tapped for Esc, Shift tapped for parentheses, Cmd rewrites | `desktop/kanata.kbd` |
| Per-application Cmd chords | Kanata layer for the focused window class | `kanata/apps.edn`, `desktop/bin/hyprkan` |
| Desktop discovery | Cmd+/ | `desktop/bin/keyguide` and `bindings.edn` |
| Leader sequences | Space, Alt+Space, Ctrl+b Space or Cmd+Space | `leader.edn`, `desktop/bin/keyleader`, generated files |
| Window focus | Ctrl+Alt+h/j/k/l | Generated `hypr/keybindings.lua` |
| Move windows | Ctrl+Alt+Shift+h/j/k/l | Generated `hypr/keybindings.lua` |
| Workspaces | Ctrl+Alt+1..0, add Shift to move a window | Generated `hypr/keybindings.lua` |
| Fullscreen, floating, close | Ctrl+Alt+f, Ctrl+Alt+v, Ctrl+Alt+w | Generated `hypr/keybindings.lua` |
| Application launch and focus | Unrewritten Cmd chords | `hypr/hyprland.lua` |
| Terminal multiplexer | Ctrl+b prefix, Cmd+digits | `herdr/config.toml` |
| Herdr terminal window | Ghostty class `com.mitchellh.ghostty.herdr` | `desktop/bin/herdr-terminal` |
| Editor commands | Space leader from `leader.edn`, then native leader keys | Doom, Neovim, IdeaVim |
| Web pages | Vimium normal mode and ? help | Vimium extension |
| Line editing | Native Ctrl and Alt keys | fish and the focused application |

Caps+Alt provides Ctrl+Alt without reaching for the bottom-left Ctrl key.
Plain Alt letters and digits belong to applications, not Hyprland. Alt+Space is the one exception, so Emacs M-SPC is not available.
Cmd+hjkl remains the existing arrow layer. Cmd+L is not Chrome's address-bar shortcut. Use Ctrl+L or Vimium `o`.
Cmd chords can differ per application. See [Application layers](#application-layers).

## Application layers

Kanata owns the keyboard. `desktop/kanata.kbd` turns each Cmd chord into the Linux chord for the same command, such as Cmd+C into Ctrl+Insert. Cmd chords it does not list reach Hyprland as Super chords.
An application whose native chord differs gets its own layer. That layer lists only the Cmd keys that differ and falls through to the shared ones.
`hyprkan` follows Hyprland's focus events. It switches kanata to the layer that `~/.config/kanata/apps.edn` names for the focused window class, and to `base` for every other window.

| Window class | Layer | Change |
| --- | --- | --- |
| `jetbrains-idea` | `intellij` | Cmd+Shift+Z sends Ctrl+Shift+Z, IntelliJ's redo. Elsewhere it sends Ctrl+Y, which deletes a line in IntelliJ. |
| `com.mitchellh.ghostty`, `com.mitchellh.ghostty.herdr` | `terminal` | Cmd+Backspace sends Ctrl+U. Elsewhere it sends Shift+Home then Backspace, and fish ignores Shift+Home. Cmd+Z, Cmd+Shift+Z, Cmd+W and Cmd+T send nothing, so they never suspend Neovim or a job, delete a path component or transpose characters. |

Emacs runs inside Ghostty and Herdr, so Hyprland sees only the terminal's class. A layer cannot single out Emacs or any other terminal program.
`herdr-terminal` starts Herdr in a Ghostty window with its own class. Cmd+Space, Cmd+Return and Cmd+B find Herdr by that class, so they never type into another Ghostty window.
`hyprkan layer` prints kanata's active layer. After a system switch, `hyprkan reload` makes kanata reread `/etc/kanata/kanata.kbd` without root.
Hold Left Ctrl, Space and Escape together to stop kanata if a change breaks typing.
`make kanata-check` replays chords through kanata's simulator and checks that every layer in `apps.edn` exists.

## One leader grammar

`leader.edn` defines one set of leader sequences. Each context enters the leader differently, then the keys mean the same thing.

| Context | Enter the leader | Space after the leader |
| --- | --- | --- |
| Neovim, Doom, IntelliJ | Space in normal mode | Command palette or Goto Action |
| Herdr | Ctrl+b Space, or Cmd+Space from any window | Searchable list of the Herdr leader actions |
| Desktop | Alt+Space | Dusk palette with the desktop leader actions and the guide entries |

Herdr and the desktop never treat a bare Space as the leader, so typing a space always types a space.
Cmd+Space sends Ctrl+b Space to the Herdr terminal. From another window it first focuses the Herdr terminal, or starts one when none is open.
The desktop leader is a Hyprland submap. It captures the following keys before any application sees them. A panel at the bottom of the screen lists the next keys. Escape or any unbound key cancels.
`keyleader listen` draws the panel. It follows Hyprland's submap events and asks Dusk to show the menu for the active submap. Hyprland starts it at login.
You can keep Alt held after Alt+Space. Each key also works with Alt.

Single keys after the leader carry the most frequent actions, as in Doom. Groups hold the rest, one object per letter.

| Keys | Meaning | Contexts |
| --- | --- | --- |
| , | Switch buffer, Herdr tab, or desktop window | All |
| ` | Last buffer or window | Neovim, Doom, desktop |
| . | Find a file, or launch an application on the desktop | Doom, IntelliJ, desktop. Neovim's own Space . finds a file |
| e | Edit scrollback in `$EDITOR` | Herdr |
| w h, w j, w k, w l | Focus left, down, up, or right | All |
| w s, w v | Split below, split right | Editors, Herdr |
| w d | Close the window or pane | All |
| w m | Maximize or zoom | All |
| w = | Balance windows | Neovim, Doom |
| b b | Choose a buffer, Herdr tab, or desktop window | All |
| b n, b p | Next or previous buffer, Herdr tab, or window in the workspace | All |
| b d | Close the buffer or Herdr tab | Editors, Herdr |
| f s, f r | Save, recent files | Editors |
| s b | Search the buffer | Editors |
| c d, c D, c r, c a, c x | Definition, references, rename, code action, diagnostics | Editors |
| s l | Copy visible text by hint (tiny-fingers), like Doom's jump to visible link | Herdr |
| o t | Terminal: vterm, a Snacks terminal, IntelliJ's terminal, or the Herdr terminal | Editors, desktop |
| o b, o i | Browser and IntelliJ workspaces | Desktop |
| p p | Switch project: projectile, IntelliJ recent projects, or the Herdr sesh picker | Doom, IntelliJ, Herdr |
| TAB . | Choose a workspace | Doom, Herdr |
| TAB ` | Last workspace | Doom, Herdr, desktop |

`keyleader --check` rejects a single key that is also a group key. Doom's Treemacs toggle stays on its upstream SPC o p. Ctrl+b f and Ctrl+b Shift+E still work.
`keyleader --check` also rejects desktop sequences that differ only by case, because Hyprland submaps cannot tell `c r` from `c R`.
`reference.html` maps every binding against Doom Emacs; see [Reference](#reference).

Run `keyleader --check` to validate the table. `make keybindings-check` also fails when a generated file is stale.

### Focus falls through to the next layer

Ctrl+h/j/k/l is the same move everywhere. In Neovim, smart-splits moves between windows, then Herdr panes. In Doom it moves between windows. In a Herdr pane, Herdr forwards the key when the foreground program keeps it under `leader.edn`'s `:terminal :keeps`, and moves between panes otherwise. Vim, Neovim and Emacs keep all four keys, so they can move within their own splits first. Pi keeps Ctrl+j/k/l, fzf keeps Ctrl+j/k for its selection, and shells keep Ctrl+l to clear the screen. Use Ctrl+b h/j/k/l to leave such a pane directly.
Ctrl+k is therefore focus, not kill-line, at a shell prompt.
At Herdr's edge, or from a zoomed pane, focus continues to the next Hyprland window. Ctrl+h no longer acts as Backspace in shells.
Ctrl+arrows resize the Neovim window, then the Herdr pane once Neovim fills that direction.

Space w h/j/k/l and Ctrl+h/j/k/l move between Neovim or Doom windows first.
At the editor's edge they run `keyleader nav`. That command focuses the neighboring Herdr pane, or the neighboring Hyprland window when the pane is at Herdr's edge.
Herdr's Ctrl+b h/j/k/l use the same command, so they also continue to Hyprland.
The desktop leader moves Hyprland windows only, because it cannot see editor windows inside a terminal.
IntelliJ stops at its own splits. IdeaVim cannot run an external command.

## Discovery

Cmd+/ opens a grouped, searchable Dusk menu in every application.
Type a group name and press Enter. Select an action with Enter.
Escape closes without running an action. `.. Back` returns to the groups.
Entries marked `guide` display advice rather than inject keys into the focused application.
Window and workspace entries execute their displayed action.
Closing never asks. Hyprland, Herdr, Ghostty and Emacs close windows, panes, tabs and frames straight away. Neovim and Emacs still ask to save unsaved files.
This is an on-demand menu, not an automatic popup after arbitrary application prefixes.

Doom and Neovim show which-key after a 350 ms pause on a prefix.
Herdr has native help at Ctrl+b ?. Vimium has native help at ? on supported web pages.
The global guide remains available on pages where Chrome forbids extensions.

## Your keybinding log

`keylog` shows the bindings you used, what each did, and which program handled it. Run `keylog` for the last 30, `keylog 100` for more, and `keylog top` for the most used. The file is `~/.local/state/keybindings/keys.tsv`, with one tab-separated row per binding.
These write to it: every leader sequence in Neovim, Doom and Herdr, Ctrl+h/j/k/l in Neovim, Doom and Herdr, the desktop leader, the Ctrl+Alt desktop bindings, and actions run from Cmd+/ or the palette.
It records bindings and their effects only, never typed text or file names. Native application shortcuts that these configs do not wrap, such as Vimium or IntelliJ keys, are not recorded.

## Coaching

Neovim uses Hardtime in hint mode. Repeated movement still works. Hardtime leaves arrow keys available and preserves the existing mouse setting.
Space u h toggles hints. Space u H opens the report.
Hardtime writes its own motion-hint log under `~/.local/state/nvim/hardtime.nvim.log`.

Doom uses `key-coach.el`. Five repeated eligible Evil motions trigger advice, with a ten-second notification cooldown.
The coach ignores insert mode, minibuffers, read-only buffers, buffers without files, explicit counts, and macros.
Space t h toggles it. Space t H shows per-command hint counts for the current session.
It stores no typed text, file names, or persistent history.

Chrome, fish, and IntelliJ have guidance in the global menu, not automatic habit detection.
No global keyboard listener or browser content script has been added.

## Changed shortcuts

| Before | Now | Reason |
| --- | --- | --- |
| Alt+w closes a window | Ctrl+Alt+w | Preserve Emacs M-w |
| Alt+f toggles fullscreen | Ctrl+Alt+f | Preserve Emacs M-f and Vimium Alt+f |
| Alt+arrows focus windows | Ctrl+Alt+hjkl | Preserve word movement and the Alt+arrow rewrite |
| Alt+digits switch workspaces | Ctrl+Alt+digits | Preserve application tabs |
| Cmd+s launches Herdr | Cmd+b | Cmd+s becomes Ctrl+s |
| Ctrl+s opens Herdr text hints | Ctrl+b f | Preserve editor save |
| Herdr edit-scrollback default collides with workspace picker | Ctrl+b Shift+E | Keep Ctrl+b e for workspaces |
| Cmd+Space opens the application launcher | Alt+Space a a, or Alt+Space Space | Alt+Space is the desktop leader |
| Cmd+Space opens the desktop leader | Alt+Space | Cmd+Space opens the Herdr leader from any window |
| Herdr Ctrl+b h/j/k/l stop at the last pane | They continue to Hyprland windows | Same behavior as the editors |
| Neovim and Doom Ctrl+h/j/k/l stop at the last window | They continue to Herdr panes and Hyprland | Same behavior as Space w h/j/k/l |
| Neovim Space c d shows line diagnostics | Space c d finds the definition, Space c x lists diagnostics | Match Doom and IntelliJ |
| IntelliJ Space w, b, c follow idea-doom-emacs | They follow `leader.edn` where the table defines them | Same meanings as the editors |
| Ctrl+b Space f copies text by hint | Ctrl+b Space s l | Doom's SPC f is the file group and SPC s l jumps to a visible link |
| Alt+Space a a/t/b/i | Alt+Space o a/t/b/i | Doom's SPC o opens terminals and the browser |
| Alt+Space o a launches an application | Alt+Space . | Two keys for the most used desktop action, like Doom's SPC . |
| Space c R finds references | Space c D | Doom pairs c d with c D |
| Doom Space e toggles Treemacs, Space t T toggles vterm | Space o p and Space o t | Upstream Doom's keys |
| IdeaVim Space b b opens recent files | The Switcher | f r already opens recent files |
| Cmd+Z, Cmd+W and Cmd+T in a terminal send Ctrl+Z, Ctrl+W and Ctrl+T | Nothing | Ctrl+Z suspended Neovim, and fish's vi insert mode has no undo on it |
| Ctrl+Shift+4 copies a region | Cmd+Shift+4 sends Shift+Print, Cmd+Shift+3 sends Print | Ctrl belongs to applications |
| Ghostty Ctrl+Enter toggles fullscreen | Ctrl+Enter reaches the program | Ctrl+Alt+f owns fullscreen |
| Pi receives bare Ctrl+h | Ctrl+h moves focus from Pi | Pi does not use Ctrl+h |
| Caps or Shift become modifiers after 500 ms alone | After 200 ms | Ctrl+click and Shift+click lose the modifier until then |

Herdr's Ctrl+b prefix still captures readline backward-char. Use Left or Cmd+h there.
Existing custom Herdr actions also override native defaults for Ctrl+b b, p, Shift+P, and o.
Cmd+n still sends a bbh composer message. This existing local override is not a new-window command in that buffer.

## Reference

`reference.html` is a single page that lists every binding by layer, compares the shared leader with Doom Emacs's `SPC` tree, and records the open inconsistencies. The comparison shows each binding's `:doom-match` from `leader.edn`, and `review-test.clj` fails when a sequence Doom binds has none. `bb desktop/build/keybindings/reference.bb` regenerates it from `leader.edn`, `bindings.edn`, `kanata.kbd`, `herdr/config.toml`, `hyprland.lua` and the installed Doom's `+evil-bindings.el`.

## Maintenance

`leader.edn` owns the leader sequences. `desktop/build/keybindings/generate.bb` writes the Neovim, Doom, IdeaVim, and Hyprland leader files from it.
Hyprland reads `hypr/hyprland.lua`. Dispatchers are data such as `["focus" {:direction "left"}]`, and `desktop/lib/hypr/dispatch.clj` renders them for the Lua config and for `hyprctl dispatch`.
`bindings.edn` owns the direct Ctrl+Alt bindings and the Cmd+/ guide entries.
Application-specific keymaps remain native configuration. Guide entries do not claim to be an exhaustive inventory.
`make keybindings-check` compares every generated file with its source. Its `test.clj` also fails when `hyprland.lua` binds a Cmd chord that `bindings.edn` does not list.
See [the installation instructions](install.md) to regenerate bindings or install Vimium aliases.

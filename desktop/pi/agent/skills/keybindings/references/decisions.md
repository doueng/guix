# Keybinding decisions

Each entry gives the date, the chord, the decision, the reason, and the status. Add an entry when a choice rejects an alternative for a stated reason, or when verification finds a conflict.

## Kanata migration

These entries supersede the keyd and keymapper items below.

- 2026-10-21. Kanata replaces keyd and the staged keymapper config. `desktop/kanata.kbd` reproduces every keyd binding. `make kanata-check` replays 21 chords through kanata 1.11's simulator. Status: simulated, and `make build` passed. Live checks wait for `make switch`.
- 2026-10-21. The keymapper Emacs block was dropped, not ported. Emacs is `emacs-no-x` inside Ghostty and Herdr, so Hyprland reports `com.mitchellh.ghostty` and a class rule never matches. The block also sent C-s for find, which this Doom config binds to `save-buffer`. Status: observed from the installed Emacs and `keybindings.el`.
- 2026-10-21. IntelliJ gets the first application layer. Its default keymap binds Ctrl+Y to Delete Line and Ctrl+Shift+Z to Redo, so the shared Cmd+Shift+Z deleted a line. Status: inferred from IntelliJ's default Linux keymap, since no custom `keymap.xml` exists. The layer itself is simulated.
- 2026-10-21. Kanata listens on 127.0.0.1:7333. moshi-hook polls 127.0.0.1:7070, kanata's documented example port, with HTTP requests. Status: observed with `ss`.
- 2026-10-21. Caps and Shift become their modifier when another key is pressed, or after 500 ms held alone. keyd sent Escape or a parenthesis for a lone press of any length. Caps then a mouse click within 500 ms now clicks without Ctrl. Status: simulated.
- 2026-10-21. Releasing Backspace within 40 ms of Cmd+Backspace leaves Super up until Cmd is pressed again. A 100 ms tap restores it. Status: simulated.

## Review fixes

These entries supersede the hold time above and the Cmd+Space and leader entries below.

- 2026-10-21. Caps and Shift become modifiers after 200 ms held alone, not 500 ms. Kanata reads only keyboards, so a click does not count as another key. Caps held under the hold time for a Ctrl+click sent Escape and clicked without Ctrl, and Shift+click typed a parenthesis. A 150 ms tap still gives Escape. Status: simulated, live after `make switch`.
- 2026-10-21. Ghostty's default Ctrl+Enter toggled fullscreen and the program never saw the key. `ctrl+enter=unbind` lets it through as CSI 27;5;13~. Alt+digits already reached programs, because Ghostty skips `goto_tab` with one tab. Ctrl+minus and Ctrl+equal still change the font size. Status: observed with ydotool in a probe window.
- 2026-10-21. Herdr runs in a Ghostty window of class `com.mitchellh.ghostty.herdr`, started by `herdr-terminal`. Every Ghostty window shared one class, so Cmd+Space typed Ctrl+b Space into a Ghostty window without Herdr. Cmd+B ran `herdr` with no terminal. Startup now opens the Herdr terminal instead of a plain Ghostty. Status: observed before and after with ydotool, and `make hypr-nested-check` covers it.
- 2026-10-21. The `terminal` kanata layer sends Ctrl+U for Cmd+Backspace in both Ghostty classes. fish ignores Shift+Home, so the shared macro turned `echo abc` into `echo ab`. Status: the fish failure observed, the layer simulated, live after `make switch`.
- 2026-10-21. Cmd+Shift+3 sends Print and Cmd+Shift+4 sends Shift+Print. Hyprland binds PRINT to a full screenshot and SHIFT + PRINT to a region copy, replacing CTRL + SHIFT + 4. Status: the Print bind observed, the kanata chords simulated.
- 2026-10-21. `hyprkan` sends the layer on every focus change, and `hyprkan reload` restores it once kanata reports the reset. kanata returns to its first layer on reload, and the old deduplication kept it there while focus stayed in windows of the same layer. Status: observed before and after with a temporary `apps.edn` entry.
- 2026-10-21. `leader.edn`'s `:terminal :keeps` pairs each program regex with the keys it keeps. Pi had received Ctrl+h, which it does not use, and fzf had lost Ctrl+j/k. Status: tests, and the rules checked against this Pi pane's live process list.
- 2026-10-21. Tiny-fingers moved from Ctrl+b Space f to Ctrl+b Space s l, and the desktop application group from Alt+Space a to Alt+Space o. Doom binds SPC f to files, SPC s l to jump to a visible link, and SPC o to open terminals and the browser. Ctrl+b f still opens tiny-fingers. Status: tests and `keyleader --verify-hypr`.

## Update from this implementation

The entries below retain the earlier investigation record. These decisions supersede the corresponding open items.

- Hyprland window and workspace bindings now use Ctrl+Alt. Plain Alt+w, Alt+f, and Alt+digits reach applications. Generated bindings live in `desktop/configs/hypr/keybindings.lua`.
- Herdr launch moved from unreachable Super+S to Super+B. Cmd+S becomes Ctrl+S in kanata, and Cmd+B runs `herdr-terminal`.
- Herdr tiny-fingers moved from Ctrl+S to prefix+f. Editor save no longer competes with the plugin. Native help stays prefix+?. Scrollback editing now explicitly uses prefix+Shift+E, leaving prefix+e for the workspace picker.
- Ghostty no longer binds Ctrl+Left and Ctrl+Right. kanata sends them for Alt+arrows, and fish moves by word itself.
- Keymapper was removed in the kanata migration. The old statement that Alt+W kills a window no longer describes Hyprland.
- Cmd+/ opens a Dusk discovery menu. It is on-demand discovery, not application prefix interception. The canonical desktop command registry is `desktop/configs/keybindings/bindings.edn`.
- Neovim Hardtime and Doom's command coach suggest motions without blocking them. Browser guidance is available in the menu. No global input capture was added.
- Vimium aliases in `desktop/configs/vimium/keybindings.txt` preserve defaults. They require merging into the extension's existing custom mappings. The supplied blob URL was not treated as a portable export.

- One leader grammar (`leader.edn`) drives Neovim, Doom, IdeaVim, Herdr, and the desktop. A global bare-Space interceptor was rejected because it would capture typed spaces and the editors' own leader. Each context enters the leader with its safe key and then shares the sequences.
- Herdr supports only a prefix plus one key, so its leader is a popup (`keyleader herdr`) on Ctrl+b Space. Its builtin `focus_pane_*` keys are unset so Ctrl+b h/j/k/l can fall through to Hyprland.
- Alt+Space is the desktop leader and Cmd+Space opens Herdr's leader. The launcher is Alt+Space o a and is also listed in the Alt+Space Space palette.
- Neovim Space c d changed from LazyVim's line diagnostics to definition, matching Doom and IntelliJ.

Verification and remaining gaps are in `desktop/build/keybindings/decisions.tsv`.

## Earlier open items

- 2026-10-20. Herdr's prefix `ctrl+b` captures readline Ctrl-B in a bbh terminal pane, so back-char is unreachable there. bbh binds no Ctrl-B, and other shells in Herdr share the same loss. Status: from config. Options are to change the prefix or accept the loss. The user decides.
- 2026-10-20. The bbh terminal front end submits on Enter and has no multi-line key, while the Emacs composer uses RET for a newline. Status: from main.clj. Decide whether the terminal needs one.

- 2026-10-20. SUPER+S opens Herdr (`hyprland.conf` line 82). keyd maps `s` in `cmd:M` to Ctrl+S (`keyd.conf` line 18), so a physical Cmd+S likely never reaches Hyprland. Status: inferred from config, not tested on hardware. Confirm with `keyd monitor` before moving the binding.
- 2026-10-20. ALT+W (kill window) and ALT+F (fullscreen) in Hyprland consume Alt+w and Alt+f before any app sees them. Emacs M-w and M-f are shadowed everywhere. Status: from config. Options are to move these binds off Alt letters or to pick another family. The user decides.
- 2026-10-20. `prefix+e` in Herdr is both `workspace_picker` (`herdr/config.toml` line 28) and, per a comment at line 74, edit-scrollback. No edit-scrollback key line exists in the config, so the comment is probably stale. Status: unverified. Check in Herdr before reusing `prefix+e`.
- 2026-10-20. ALT+left and ALT+right (focus) in Hyprland consume Alt+arrows before Ghostty sees them. Ghostty's `alt+left=esc:b` and `alt+right=esc:f` never fire. Status: from config.

- 2026-10-20. keymapperd and keyd both need an exclusive grab on the keyboard. keyd.conf `[ids] *` already grabs it. Options are to make keymapper the only remapper, which moves the Cmd layer and the capslock and shift overloads into it, or to chain keyd's virtual device into keymapper. The user decides. Status: from upstream README and keyd.conf, not run on hardware.
- 2026-10-20. keymapperd fails to link in an unprivileged build (`/tmp/keymapper-src`, `collect2: ld returned 1`). The keymapper client and keymapperctl build. Status: observed. The config parses with the client. Status: observed.
- 2026-10-20. Emacs copy stays on Control{Insert}, not M-w. Alt+W is consumed by Hyprland and kills the window, so `Alt{W}` never reaches Emacs. Status: from hyprland.conf line 85.
- 2026-10-20. keyd sends C-f for Cmd+F, which Emacs reads as forward-char. The keymapper `Emacs` block maps find to C-s. Status: inferred from keyd.conf and Emacs defaults. The Emacs window class is not confirmed on this machine.
- 2026-10-20. Emacs cut moves from keyd's Shift+Delete, a no-op there, to C-w (kill region). Redo, reopentab, newwindow and reload are unbound in Emacs so they do not fall through to yank or isearch. Status: from keymapper.conf.

## Recorded

- Herdr prefix stays `ctrl+b`. Its config comment keeps the tmux shape familiar, and it stays out of the Cmd family.
- Cmd+digit maps to Ctrl+digit in kanata so Herdr's `ctrl+1..9` tab switch works from Cmd. Source: `herdr/config.toml` comment and `kanata.kbd`.
- `prefix+p` is annotate capture because `prefix+a` (agent picker) and `prefix+e` are taken. Source: `herdr/config.toml` comments.
- bbh composer sends on C-n, C-c C-c and M-RET. C-n matches Cmd+N, because kanata maps Cmd+N to C-n, so Cmd+N also sends. Source: `agent-shell.el` and `kanata.kbd`.
- bbh composer pages the transcript on C-v and M-v and does not paste on C-v. Cmd+V still pastes through kanata (Cmd+V to Shift+Insert), so Ctrl-V stays free. The composer overrides Doom's global `C-v` paste locally. Source: `agent-shell.el` lines 311-312, `keybindings.el` lines 191-192.
- bbh transcript follows magit's section keys in evil normal state. n and p move between headings, M-n and M-p between prompts, ^ goes up, S-TAB toggles all tool results, and q buries the buffer. These override evil's n (search next), p (paste) and q (macro record), but only in the read-only transcript. The composer keeps C-c C-c to send and C-c C-k to stop, matching magit's commit-message convention. Source: `agent-shell.el` evil block, `bbh-transcript-navigates-like-magit` in `test/agent-shell-test.el`. Status: tests drive the keys through `key-binding`, because `execute-kbd-macro` did not reach the evil keymap in batch.
- bbh terminal uses C-c to interrupt a running turn, C-c at the prompt to clear the line, and C-d to quit. Nothing else binds these chords, since Ghostty copy goes through Cmd. Source: `main.clj` lines 11, 55, 61-63.
- C-c C-p peeks at the last response. agent-shell defines it, not bbh, and `test/agent-shell-test.el` line 122 asserts it resolves to `agent-shell-viewport-reply`. Status: from the test source, not run here.

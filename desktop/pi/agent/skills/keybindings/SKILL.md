---
name: keybindings
description: Choose, place, verify, and discover keybindings across keyd, Hyprland, Ghostty, Herdr, Neovim, Doom Emacs, IdeaVim, fish, Chrome, and Vimium. Use when adding a shortcut, checking conflicts, or changing keyboard coaching.
---

# Keybindings

Read `desktop/configs/keybindings/README.md` for ownership and the current families.
Read `references/decisions.md` before reusing a disputed shortcut.

## Inventory first

Run `bb <skill-dir>/scripts/inventory.bb <repo-root>`. Rows whose layer starts with `leader` come from `leader.edn`, one per context, with the leader entry key and the file that applies the binding.
The parser is a partial inventory, not proof that a chord is free. It omits multi-line Doom forms and some Herdr arrays.
Inspect the owning file and upstream defaults as well.
Use `keyguide --list` for the curated discovery menu.

## Choose the owner

1. Name the action's scope and frequency. If the action exists in more than one of Neovim, Doom, IntelliJ, Herdr, and the desktop, it belongs in `desktop/configs/keybindings/leader.edn`. Give it the same leader keys in every context that has it.
2. Check at least three candidate chords in the relevant family.
3. Trace candidates through `desktop/keyd.conf` before checking Hyprland and the focused application.
4. Inspect `hyprctl -j binds` and exercise the chord through `ydotool` before declaring it reachable. After a leader change, `keyleader --verify-hypr` confirms that Hyprland loaded the generated submaps in order.
5. Put desktop window and workspace actions in `desktop/configs/keybindings/bindings.edn`.
6. Regenerate `desktop/configs/hypr/keybindings.lua` with `bb desktop/build/keybindings/generate.bb`.
7. Keep editor, browser, shell, and multiplexer actions in their native configuration.
8. Run `make keybindings-check` and the affected application's checks. Doom changes also require `make doom-check`.
9. Reload and verify the real application. Record blocked checks rather than calling them passes.

Consistency 35. Ergonomics 25, weighted toward frequent actions. Conflict avoidance 20. Memorability 15. Portability 5.
Hard constraints override the score.

## Hard constraints

- Ctrl+Alt is desktop control. Add Shift for the corresponding move action.
- Plain Alt belongs to applications. Do not globally consume Emacs M-f, M-w, or IdeaVim Alt+digits. Alt+Space, the desktop leader, is the only exception.
- Cmd is partly rewritten by keyd. Cmd+S becomes Ctrl+S and Cmd+L becomes Right. Do not assume physical Cmd means Super.
- Caps held is Ctrl. Caps tapped is Escape. Keep that escape path available.
- Never make a bare Space the leader where Space can type text. The editors use Space in normal mode, Herdr uses Ctrl+b Space or Cmd+Space, and the desktop uses Alt+Space. Space after any leader opens that context's command palette, so no leader group may use Space.
- Bare Ctrl+h/j/k/l is window focus in every layer. Herdr forwards it to programs matching `leader.edn`'s `:terminal :passthrough` and moves focus elsewhere. Do not give Ctrl+h/j/k/l another meaning in a shell, editor or TUI without adding that program to the pass-through list.
- Window focus falls through from the editor to Herdr to Hyprland through `keyleader nav`. Keep editor Ctrl+h/j/k/l and Herdr Ctrl+b h/j/k/l on that path.
- Hyprland reads `hypr/hyprland.lua`. In a Lua session `hyprctl dispatch` takes Lua such as `hl.dsp.focus({ direction = "left" })`, not the old dispatcher names. Write dispatchers as data and run them with `desktop/lib/hypr/dispatch.clj`.
- Generated submap actions use `keys.leave`, which runs the action and then leaves the submap. The catch-all uses `keys.cancel`, because Hyprland also runs the catch-all after a bound key. `make hypr-nested-check` exercises both.
- Do not hand-edit generated files: `shared-keys.fnl`, `shared-keys.el`, `shared-keys.vim`, `hypr/leader.lua`, and `hypr/keybindings.lua`. Edit `leader.edn` and run the generator.
- The keymapper config is staged, not active. Do not start keymapperd alongside keyd's exclusive grab or claim its overrides work.
- Ctrl belongs to applications. Put new Herdr actions under Ctrl+b. Its native defaults must be checked as well as custom commands.
- One chord has one action in each context. Name any replaced binding and its reason.
- Keep paired operations consistent. Rare commands use mnemonic prefix sequences rather than another global modifier chord.
- Chrome native shortcuts and Vimium page-mode mappings are separate contexts. Use the installed extension's command names when editing `desktop/configs/vimium/keybindings.txt`.
- Never edit live Chrome LevelDB or commit a private settings export. A blob URL is not a portable backup.

## Discovery and coaching

Cmd+/ opens `desktop/bin/keyguide`. Entries marked `guide` explain native shortcuts without injecting input.
Doom and Neovim use which-key. Herdr uses Ctrl+b ?. Vimium uses ? on supported pages.
Do not describe the global picker as automatic prefix detection.

Neovim uses Hardtime in hint mode. Doom's `key-coach.el` observes eligible commands, not text.
Keep counts, macros, insert mode, minibuffers, and sensitive application input out of new coaching logic.
Do not add a global keystroke logger. Keep a visible off switch and test that movement still works when a hint fires.

The bbh composer overrides some global Doom keys. Cmd+N sends a message there, C-v pages the transcript, and Cmd+V pastes.
Treat those bindings as mode-local before changing global editor behavior.

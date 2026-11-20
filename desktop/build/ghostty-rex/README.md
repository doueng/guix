# ghostty-rex

A [Rex](https://rex.superlogical.com) client with no Rust: Ghostty draws, Rex
holds the sessions, and Babashka does everything else (T207; design in
docs/rex-client-languages.html, option 1). It is the third choice of
`%terminal-mux`, next to Herdr (the default) and rexmux.

One Ghostty window (class `dev.engstrand.Rex`) shows one Rex session the way
Herdr does: a tab bar, the active tab's panes with title rows and dividers, a
status line and an echo line. Ghostty's own tabs and splits are not used.

- **Client** (bb, `src/rexg/`): the Rex control connection and data streams
  (`rex.clj`, `ws.clj`), keys from Ghostty through the Kitty keyboard
  protocol (`keys.clj`), the model (`model.clj`, a port of rexmux's model.rs
  with the same rules and tests), the chrome (`screen.clj`), tiny fingers
  (`hints.clj`) and the wiring (`main.clj`).
- **Sidecar**: rexmux's `sidecar/rexmux/`, run in-process. It holds the command
  registry, M-x generated from Rex's API, the leader.edn keymap, agent status
  from OSC 7501, notifications, and bbh routing (`bbh say --from rex`).
- **rexg-render** (C, `csrc/rexg-render.c`): one libghostty-vt terminal per Rex
  block. It decodes the stream's Ghostty snapshot and composes the visible
  panes and the client's chrome into the terminal it runs in. Pane bytes never
  reach Ghostty, so terminal queries get one reply, from Rex. The Guix package
  `rexg-render` builds it on the `libghostty-vt` package, both in
  modules/engstrand/packages/ghostty.scm.

Edit any `.clj` and reopen the window (Super+B), with no build step. `make
ghostty-rex` compiles rexg-render into `target/` in under a second for work on
the C half; `REXG_RENDER=target/rexg-render` makes the client use it.

## Switching and rolling back

    make mux MUX=ghostty-rex   # set %terminal-mux, home-apply, reload Hyprland
    # Super+B or Super+Return opens it; Super+Return/E/D/G pick tabs 1-4
    make mux MUX=herdr         # back to Herdr
    make mux MUX=rex           # or to rexmux

`make mux` edits the one line `(define %terminal-mux 'herdr)` in
modules/engstrand/config.scm and runs `make home-apply`, which writes the word
to `~/.config/engstrand/mux`. Hyprland, `mux-terminal`, `workspace-or-herdr-tab`,
keyleader and bbh's notifications read it (desktop/lib/mux.clj). Herdr, rexmux
and the Rex server stay installed whatever the choice. `guix home roll-back`
also restores the previous choice. The Rex sessions persist across a switch.
Rex keeps them, and the ghostty-rex and rexmux windows show the same `main`
session.

## Configuration

`desktop/configs/ghostty-rex/config.edn`, linked to
`~/.config/ghostty-rex/config.edn`, has the same keys as rexmux's: servers,
leader file, term-nav, shell, and the `:startup` session (shell, bbh, pi and
jjui tabs in ~/guix). `desktop/configs/ghostty-rex/ghostty` is loaded after
your Ghostty config. It clears Ghostty's tab and split keys, so every key
reaches the client, and keeps copy, paste and font size.

## Tests

- `make ghostty-rex-test`: unit tests of keys, model, hints and screen (lint runs them).
- `make ghostty-rex-verify`: the window end to end on the hidden test display
  against a throwaway Rex server, then Bombadil over `ghostty-rex attach` with
  rexmux's specs (test/bombadil/*.ts).
- `make rexmux-tla` model-checks the stream, link and agent rules this client
  shares with rexmux.

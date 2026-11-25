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
  with the same rules and tests), the chrome (`screen.clj`), the names it
  shows for tabs, panes and sessions in place of Rex IDs (`names.clj`, from
  Rex's process, title and pwd of each block and their `*_changed` events),
  tiny fingers (`hints.clj`) and the wiring (`main.clj`).
- **Sidecar**: rexmux's `sidecar/rexmux/`, run in-process. It holds the command
  registry, M-x generated from Rex's API, the leader.edn keymap, agent status
  from OSC 7501, notifications, and bbh routing (`bbh say --from rex`).
- **rexg-render** (C, `csrc/rexg-render.c`): one libghostty-vt terminal per Rex
  block. It decodes the stream's Ghostty snapshot and composes the visible
  panes and the client's chrome into the terminal it runs in. The snapshot's
  visible screen shows as soon as it is in, before history and images (pi's
  pane can hold tens of MB of them); a pane shown before keeps its last
  screen until then, and a new one says it is loading. Pane bytes never
  reach Ghostty, so terminal queries get one reply, from Rex. The Guix package
  `rexg-render` builds it on the `libghostty-vt` package, both in
  modules/engstrand/packages/ghostty.scm.
- **Pop out** (docs/ghostty-rex-one-parser.html): M-x Pane: pop out
  runs `ghostty-rex popout`, a Ghostty window of class `dev.engstrand.RexPane`
  showing one pane with two parsers, Rex's and that Ghostty's. Its relay
  (`pane.clj`) takes and sizes the block, prints the stream's snapshot once
  through `rexg-render --vt` (libghostty's formatter), passes the live bytes on,
  and sends keys back less Ghostty's second answers to the program's queries
  (`replies.clj`). The main window leaves a popped-out block's size alone until
  that window closes.

Edit any `.clj` or config.edn and run `rex -C ghostty-rex do mx.client.reload` (M-x Client:
reload): the client starts anew on the same session in the same window, with
no build step. `make build NAME=ghostty-rex` compiles rexg-render into `target/` in under
a second for work on the C half; `REXG_RENDER=target/rexg-render` makes the
client use it. docs/ghostty-rex-dev.html covers the whole loop, logs and
troubleshooting.

## Switching and rolling back

    make mux MUX=ghostty-rex   # set %terminal-mux, home-apply, reload Hyprland
    # Super+B or Super+Return opens it; Super+Return/E/D/G pick tabs 1-4
    make mux MUX=herdr         # back to Herdr
    make mux MUX=rex           # or to rexmux

`make mux` edits the one line `(define %terminal-mux 'herdr)` in
modules/engstrand/config.scm and runs `make home`, which writes the word
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

- `make test ONLY=ghostty-rex`: unit tests of keys, model, hints and screen (lint runs them).
- `make verify ONLY=ghostty-rex` (`test/rexg/verify.clj`, about 5 minutes): a real
  Ghostty on the hidden test display against a throwaway Rex server, through
  the parity list in docs/rex-parity.html: startup tabs and their programs,
  `rex -C ghostty-rex do mx.tab.select_3` (Super+D), tiny fingers, mouse drag
  and wheel, rename, split cwd, resize, bare Ctrl+h and Ctrl+L (keyleader
  term-nav: focus moves left, a shell's screen clears), copy mode and search, Ctrl+Shift+V,
  project sessions, jjui and bbh's inbox layers, bbh say --from rex, session
  rename, a desktop notification for a blocked agent, a simulated reboot, and
  switch timing: in a pty client, a pi tab holding a 24 MB image (as pi's
  inline screenshots make it) must paint within 300 ms of a project switch
  (`bb switch-timing` runs only this, in about a minute). Then Bombadil types into
  `bb attach` with rexmux's specs (`../rexmux/test/bombadil/rexmux.ts` and
  `startup.ts`) and its own `test/bombadil/names.ts` (tabs rename on cd, on a
  program and by hand, and no Rex ID shows), 60 s each (`--time N`,
  `--no-bombadil`, `--spec=names` for one, `--keep`), with
  `REXG_SYNC=0` so the renderer drops synchronized output and Bombadil
  judges the screen after a quiet gap, not at a pane-output frame. The
  client runs with Hyprland's PATH, which lacks ~/.local/bin, as the real
  window does.
- Cursor (T223): `bb verify:quint` (make test) and `bb verify:quint-mc` (make verify) check
  `verify/quint/cursor.qnt`, a model of the cursor across programs, panes, tabs, snapshot
  replays and overlays, whose `before` module keeps the traces that broke the old renderer.
  `bb verify` also runs Bombadil's `test/bombadil/cursor.ts` over modal panes
  (`test/rexg/modal.bb`) and fish with the shipped vi cursor, judging every synchronized frame;
  `bb bombadil:cursor` runs only that. `REXG_TRACE=FILE` keeps a copy of rexg-render's output.
- Sizes: `verify/quint/resize.qnt` models a pane's size against its bytes: Rex puts
  a resize in the pane's stream, bb sends it to rexg-render from the stream's thread, and
  rexg-render applies it at its place among the bytes, so every byte lands at the size it
  was written for. Its `before` and `bbOnly` modules keep the traces that broke it.
  `desktop/build/pbt`'s `rexg-stream-order` suite checks the splitter's order across any
  chunks, and `bb mbt rexg-resize-mbt` replays resize.qnt's traces on it.
- Pop out: `verify/quint/popout.qnt` models who sizes a popped-out pane; the
  `rexg-replies` suite in `desktop/build/pbt` checks the reply filter against a model;
  `bb verify` pops the shell pane out on the test display, types in it, and checks
  Ghostty's answer to a DA1 query never reaches the pane.
- `make verify ONLY=rexmux` model-checks the stream, link and agent rules this client
  shares with rexmux. The sidecar runs in-process here, so the model's Crash
  and Restart never happen, and a sidecar exception still answers its request.

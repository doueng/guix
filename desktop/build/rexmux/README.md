# rexmux

A client for the [Rex](https://rex.superlogical.com) terminal server, built as an
opt-in alternative to Herdr (T194 design: docs/rex-vs-herdr.html and
docs/rex-client-design.html; T207 build). Herdr stays the default.

- **Window** (Rust, `src/`): GPUI at Dusk's pin, libghostty-vt terminals fed
  straight from Rex data streams. Owns panes, keys and drawing.
- **Sidecar** (Babashka, `sidecar/rexmux/`): one child process over EDN lines.
  Owns the command registry (generated from Rex's `rex api list --json`,
  leader.edn entries with `:rex`, local commands, extensions), the `mx` keymap
  layer, M-x, agent status from OSC 7501, and workflows. Nothing per key goes
  through it.

## Switching between Herdr and Rex

Herdr is the default. One word in `modules/engstrand/config.scm`,
`(define %terminal-mux 'herdr)`, picks the multiplexer; Home writes it to
`~/.config/engstrand/mux`, which Hyprland's Super+B, `mux-terminal`,
`workspace-or-herdr-tab`, keyleader and bbh's notifications read
(`desktop/lib/mux.clj`).

- `make mux MUX=rex` builds rexmux, sets the word to `rex`, runs
  `make home` and reloads Hyprland. Super+B then opens the rexmux window,
  which starts the local Rex server and its `:startup` session from
  `desktop/configs/rexmux/config.edn` (shell, bbh, pi and jjui tabs, as in
  Herdr). docs/rex-parity.html lists what matches Herdr and how each is tested.
- `make mux MUX=herdr` switches back the same way. `guix home roll-back` also
  works, since the choice is part of the Home generation. Both stay installed,
  and Herdr's sessions are untouched while Rex is in use.

## Protocol (sidecar)

One EDN map per line, each with `:op` and `:seq`. Each direction numbers its
messages from 1; the receiver drops repeats and reports gaps. A request's
`:id` gets exactly one `:reply`. See `sidecar/rexmux/proto.clj`.

## Checks

| Command | What |
| --- | --- |
| `make test ONLY=rexmux` | sidecar unit and property tests (in `make lint`) |
| `make verify ONLY=rexmux` | TLC on `verify/tla/Rexmux.tla`: link, streams, agents |
| `make verify ONLY=rexmux` | live tests on a throwaway Rex server (private socket) |
| `make build NAME=rexmux` | build the window (`build.bb`, Guix profile from `manifest.scm`) |

Tests never touch your Rex at /run/user/UID/rex: `test/rexmux/testserver.clj`
starts its own server with `--listen` on a temp socket and `--restore=false`.

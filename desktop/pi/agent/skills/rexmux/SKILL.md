---
name: rexmux
description: "Read and drive the person's Rex terminal sessions with rexctl: list sessions, tabs and panes, read a pane's screen or scrollback, type text or keys, split or open panes, run commands and read their output, watch for output or an agent's status, snapshot every pane, and run the ghostty-rex or rexmux app's M-x commands. Use when the person mentions Rex, rexmux, ghostty-rex or a Rex pane, tab or session, or asks you to look at or act in their terminal while it runs Rex. Not for Herdr."
---

# Rex panes with rexctl

Rex is the terminal server behind the ghostty-rex and rexmux windows. It holds
sessions; a session holds tabs (Rex calls them windows); a tab holds panes (Rex
calls them blocks). `rexctl` wraps the `rex` CLI in stable verbs that print JSON
(`--edn` for EDN; `read` prints text). Its usage is the authority for flags:

```bash
rexctl --help
```

Errors are JSON on stderr with exit 1; usage errors exit 2. rexctl never starts,
stops or restarts a Rex server.

## Where you are

```bash
rexctl whoami
```

`in_rex` true means you run in a Rex pane: `pane` (`$REX_BLOCK`) is yours and
`session` (`$REX_SESSION`) your session, and verbs default to them. False means
you run elsewhere, such as a bbh Worker or an Emacs shell: name every pane, and
`tab` needs `-s SESSION` when several sessions exist.

```bash
rexctl ls                  # every terminal pane, every session
rexctl ls -s main          # one session
```

Each pane row has `session`/`session_label`, `tab`/`tab_index`/`tab_label`,
`pane` (a full `block:<uuid>` ID), `label`, `cwd`, `fg` (the foreground program),
`at_prompt` (its shell is in the foreground), `exited` (exit code of a finished
pane), `status` (an agent's OSC 7501 report: `blocked`, `error`, `done`, `working`
or `idle`, with `app`, `kind`, `msg`), `self`, `focused` (the active tab's focused
pane) and `viewed` (a Rex app shows the session).

Name panes by the full ID from a reply. A label or ID suffix also works when it is
unique; rex lists the matches when it is not. IDs belong to one server, and
they stay the same however the app draws the session.

## Read a pane

```bash
rexctl read block:...                 # last 80 lines, soft wraps joined
rexctl read block:... --lines 400     # more scrollback
rexctl read block:... --visible       # just the screen as drawn
rexctl read block:... --format html   # colors as evidence (or vt)
```

`--visible` is the screen alone, as the person sees it. A full-screen program
(an editor, pi, jjui) keeps only its current screen; earlier ones are not in
scrollback.

## Run something and read its output

A command with a result you need: `exec` opens a pane beside yours (or `--tab`),
waits, and returns the command's `exit` code (rexctl itself exits 0) and its
`output`. The pane stays open for the person. Pass the command as separate words;
shell syntax needs `-- sh -c '...'`. Outside Rex, anchor it with `--anchor block:...`.

```bash
rexctl exec --dir below --label tests -- make test
```

A shell you keep: split beside a pane (yours when you name none), then `run`
at its prompt.

```bash
rexctl split block:... --dir right --cwd "$PWD" --label work   # -> {"pane": "block:..."}
rexctl run block:... 'jj log -n 5' --wait               # -> {"ok": true, "output": "..."}
rexctl run block:... 'make serve'                        # no --wait for servers
rexctl wait block:... --match 'listening on' --new --timeout 60000
```

Without `--dir`, a split goes right in a wide pane and below in a tall one.
New panes and tabs keep the person's focus unless you pass `--focus`. `run`
types the command and Enter, and refuses when the pane's shell is not at its
prompt; read the pane first. Its `output` starts at the echoed command line. `wait --new` ignores matches already on screen, such
as the echo of the command you typed; a plain `--match` can hit that echo.
`wait --prompt` waits for a shell's command to end, `--exit` for a pane's program.

## Type into a program

```bash
rexctl send block:... 'some text'          # literal text, no Enter
rexctl keys block:... enter                # keys: enter, ctrl+c, escape, up, shift+tab ...
```

## Watch an agent

A program that reports OSC 7501 status shows it here (bbh's agent-shell does).
Many agents report nothing: then `fg` names the program, and reading the pane
tells you whether it waits.

```bash
rexctl status block:...
rexctl wait block:... --status done,idle,blocked,error --timeout 600000
```

`blocked` means it waits for a person, often an approval; tell the person rather
than answering it yourself unless they asked you to. `done` and `idle` mean ready
for input, though an approval dialog the agent did not report can still be up:
read the pane before typing.

## Snapshot, servers, the app

```bash
rexctl snapshot                       # every pane's text and the layout, written under a temp dir
rexctl servers                        # this machine's server and your Rex hosts
rexctl -S studio ls                   # any verb on another server (label or address)
rexctl ls --all-servers               # every server's panes, each tagged with server
rexctl mx --grep split                # the app's M-x commands (ghostty-rex, rexmux)
rexctl mx mx.tab.select_2             # do one in the person's window
```

`mx` actions act on what the person sees: switching tabs or sessions, closing,
reloading the client. Run them only when the person asks for that.

## Beyond the verbs

`rex skill show` prints Rex's own guide to the raw CLI. `rex api list`,
`rex api describe METHOD --json`, `rex block inspect BLOCK --json` (a pane's methods
and events) and `rex api call` / `rex block call` cover what rexctl does not, such as
floating layers, resizing and renaming. `rex events -s SESSION --json` streams
events; stop it when done.

## Safety rules

- Type only into panes you created or the person named. `send`, `keys`, `run`
  and `close` refuse your own pane and any pane that is both `focused` and
  `viewed`, where the person may be typing, even at a prompt. Pass `--force` only when the person
  told you to act there.
- Keep focus: never pass `--focus` or run `mx` actions unless the person asked to
  see the result.
- Close only panes you created (`rexctl close block:...`), and tabs only with
  the person's say-so. Never close a session or kill Rex.
- Never run `rex server stop` or restart, and never kill the rex process: it ends
  every program in every pane. `rex server status`/`stop` ignore `REX_SERVER` and
  act on the real server.
- Experiments go to a throwaway server, never the person's:
  `desktop/build/rexmux/test/rexmux/testserver.clj` starts one on a temp socket;
  set `REX_SERVER` (or `-S`) to its address.
- A timed-out create may have worked: `rexctl ls` before you retry.

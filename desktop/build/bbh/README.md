# bbh

A coding agent harness in babashka. Each conversation gets its own bb REPL process, and the model's single tool, `clojure`, evaluates code in that REPL. The model owns the REPL. It can require anything bb ships, keep helpers in memory for the whole conversation, and change its own system prompt and tool limits by redefining `bbh.env/manifest`.

## Front ends

From a project directory, `emacs-bbh` opens your normal Doom Emacs with a bbh session ready. It accepts normal Emacs arguments, including `emacs-bbh -nw` for terminal mode. The installed Emacs build is currently terminal-only. `emacs-bbh --resume ID` continues saved conversation ID. Inside herdr, bbh reports that command with its state, so a herdr restart reopens each pane's conversation.

In Emacs, `M-x eng/bbh` starts a session in [agent-shell](https://github.com/xenodium/agent-shell), which runs `bbh acp` and speaks the Agent Client Protocol over stdio. `M-x eng/bbh-cider-connect` attaches CIDER to that session's REPL. The Doom side lives in `desktop/configs/doom/agent-shell.el`.

The Emacs layout has a transcript above a persistent composer. The composer keeps your draft during replies and cancellation. The transcript is a read-only org buffer. Each prompt is a heading, replies are converted from Markdown to org, and each tool call shows its full code followed by a `#+begin_result` block that starts folded. `TAB` on a result toggles it, and the usual org commands navigate and fold the rest. agent-shell's own shell buffer still drives the session but stays out of the layout.

| Key | Action |
| --- | --- |
| `C-n` | Send, or queue while the model is working. |
| `RET` | Insert a newline. |
| `C-c C-k` | Interrupt the current turn after confirmation. |
| `C-c C-t` | Expand every tool result, or fold them all. Works in the composer and the transcript. |
| `M-v`, `C-v` | Page the transcript without leaving the composer. |
| `M-p`, `M-n` | Browse prompt history. |
| `C-c C-p` | Peek at the last response. |

The composer header shows the context in use, how much of the latest model request came from the prompt cache, and the session's cost, for example `ctx 45k/1m · cache 98% · $0.04`. A request reads `cache miss` when less than half of the previous request's input was read back from the cache, and `cache cold` when there was no previous request to reuse. After each turn the transcript adds one line with the turn's requests, input, cached share, any misses, output and cost.

`M-x eng/bbh-layout` restores the split. The composer header shows whether `C-n` sends immediately or queues a prompt. Sending `/new` works as it does in Pi. It ends the session, starts a fresh one in the same windows, and clears the transcript. `/quit` exits Emacs as `C-x C-c` does, even during a turn. `/resume ID` and `/resume last` replace the transcript with the saved conversation, and `emacs-bbh --resume ID` shows it the same way. A replayed tool result always shows as done, because the conversation does not record failures. An id bbh cannot resume, such as the current conversation, leaves the transcript alone, and `/resume` waits for a running turn.

In a terminal:

```sh
bbh                                   # new conversation
bbh --resume last                     # continue the newest transcript in a fresh REPL
bbh --model openrouter/deepseek/deepseek-v4.1-flash
```

Both front ends share the commands `/help`, `/model`, `/port`, `/restart`, `/resume` and `/usage`. `/resume` lists the ten newest other conversations, and `/resume ID` or `/resume last` moves the session to one of them in a fresh REPL. The REPL's port file stays in the directory the session started in, so `eng/bbh-cider-connect` keeps working. A `/word` that neither bbh nor the env handles is an error rather than a prompt, and `/help` lists both kinds of command. Ctrl-C, or agent-shell's interrupt, aborts the model's turn and kills the REPL when an evaluation is running.

## Models

Model ids are `PROVIDER/MODEL`, and `BBH_MODEL` overrides the default, `claude/haiku`.

- `claude/...` runs Claude Code on the Claude subscription with `--effort` from the manifest. Claude Code owns the agent loop, its prompt caching and its output limit, and calls the `clojure` tool through `bbh.mcp`, an MCP server that forwards each call to the session over a loopback socket. Its rate-limit events feed `/usage`.
- `openrouter/...` runs bbh's own loop against OpenRouter, with the key from `OPENROUTER_API_KEY` or Pi's `auth.json`. Each request sends the conversation id as `session_id` for sticky routing, `reasoning.effort` mapped to the nearest level the model supports, and `max_tokens` up to 128,000. Anthropic models get OpenRouter's automatic `cache_control` breakpoint, and their reasoning details are sent back unchanged across tool calls. Rate limits, server errors and failed connections are retried five times with backoff, honoring `Retry-After`.
- `codex/...` runs bbh's own loop against the ChatGPT subscription over the Codex Responses API, with the login Pi stores as `openai-codex` in `auth.json` and refreshes. Each request sends the conversation id as the `session_id` header, which is where this backend keys its prompt cache, plus `reasoning.effort` and the whole transcript with `store: false`, so no state lives on the server. The tool schema is flattened to the Responses form, and subscription usage arrives as token counts without a dollar cost.
- `script/PATH` replays an EDN file of replies, for tests.

Switching models mid-conversation keeps the transcript. A Claude Code session that starts late receives the earlier transcript with the next prompt, and a resumed one receives whatever another model said since it last took part.

## The env

`desktop/configs/bbh/init.clj` is stowed to `~/.config/bbh/init.clj` and loaded into every new REPL. It defines:

- `bbh.env/manifest`, read before every model request: the system prompt, the skill index and project instructions: `~/.config/bbh/AGENTS.md`, then `AGENTS.md` (or `CLAUDE.md`) in each directory from the repository root down to the working directory, nearest last. `bbh.repl/defaults` lists its other keys. `:effort` defaults to `medium` and `:max-tokens` to the model's limit, capped at 128,000. Changing `:system`, `:tool-description` or `:effort` mid-conversation costs one cache miss.
- `bbh.env/expand-prompt`, which rewrites user input. It expands `/skill:NAME`, starts new conversations in poteto-mode, and answers `/poteto-mode-off` without the model.
- `bbh.env/commands`, which agent-shell offers for completion.
- `jev-classify-task`, the TypeSafe Jev router poteto-mode calls, plus the `sh`, `read-lines` and `edit!` helpers.

Skills come from Pi's directories, so the pstack pack and project skills load as they do in Pi.

## Layout

- `src/bbh/session.clj` owns a conversation: transcript, REPL, commands, events and the bbh tool loop.
- `src/bbh/claude.clj` and `src/bbh/mcp.clj` run turns through Claude Code.
- `src/bbh/acp.clj` translates events into ACP updates. `src/bbh/main.clj` is the terminal front end and the entry point.
- `src/bbh/delimiters.clj` heals the model's code before it runs, as pi-sci's `src/delimiters.ts` does. It appends the closers of a form truncated within its last 10 lines, or drops extra closers at the very end, and adds an `[auto-repair]` line to the result. Anything else runs unchanged.
- `src/bbh/repl.clj` starts the REPL process and evaluates over nREPL. `src/bbh/llm.clj` streams OpenRouter.
- `src/bbh/runs.clj` owns `runs.jsonl`, the LangSmith-style run tree. `session.clj` and `claude.clj` write it.
- `src/bbh/inbox.clj` is the mailbox and presence a session shares with the website. `src/bbh/web.clj` is the website's server, and `src/bbh/web/` is the page.
- `src/bbh/usage.clj` reports Claude, Codex and OpenRouter limits, after Pi's `pi-usage` extension.

Conversations live in `~/.local/share/bbh/conversations/<id>/` as `messages.jsonl`, `runs.jsonl` (the [run tree](eval/README.md#runs-and-metrics) of timings, tokens and failures), `prompts.jsonl` (each distinct system prompt, tool description, model and effort, with the message count where it took effect), `outputs/`, `repl.log`, `repl.port`, `continued-in` once the conversation has rolled over and, for Claude, `claude-session` and `claude.log`.

## Website

`bbh web` serves a page for the phone, with a tab for each live Emacs session, its whole conversation and a composer. Swiping sideways on the conversation moves between tabs. `modules/engstrand/features/bbh-web.scm` runs it as the `bbh-web` Home service on `127.0.0.1:8766`, and it publishes itself with `tailscale serve` at `https://<node>.<tailnet>.ts.net:8443`. The page is ClojureScript run in the browser by [Scittle](https://github.com/babashka/scittle) with Replicant for rendering, so there is no build step. `src/bbh/web/` holds it, including Scittle 0.8.33's `scittle.js` and `scittle.replicant.js`, copied unchanged from its npm package. `bb test` loads the `.cljc` files there too. Code is highlighted by `@pierre/highlights`, the highlighter behind jjui's diffs. `make bbh-web-highlights` bundles it into `highlights.js`, and the page renders its tokens as hiccup. Its theme is `githubLightHighContrast`, the only light theme that measured 4.5:1 on every token against the code background.

The server shares nothing with a session but files in the conversation's rollover root, so a rollover keeps the tab:

- `agent.json` is written by the session's `bbh inbox-poll` and names its pid, project and busy state. A live pid is a live tab. A terminal `bbh` has no poller, so it has no tab.
- `mailbox/ID.json` is one message from the page. The poller hands it to Emacs, which runs it through agent-shell's queue, which waits out a running turn and leaves the composer draft alone. The prompt then shows in the transcript like one typed in the composer. Emacs acks when the turn holding it completes, including a cancelled one, and the poller deletes the file. A failed turn is not acked and is sent again after the next completed turn, so a broken model cannot loop. Delivery is at least once. A crash partway through a turn can repeat a message. `/resume` moves the poller, and a message to the old conversation waits in its mailbox until it is resumed.
- `images/ID-N.jpg` is a picture from the page, which shrinks each one to 2048 px in the browser, so HEIC photos arrive as JPEG. Prompts are text, so the message names the file in a line telling the agent to view it with `look`. The page shows the picture instead of that line, and Emacs shows the line.
- `messages.jsonl` is read as it is, so the page shows what Emacs typed too. The page polls every 2 s while visible, so a reply appears when its message is complete rather than token by token.

A message reaches an agent with a shell, so the server listens only on loopback, requires `Tailscale-User-Login` to match the node's owner, and takes a POST only with JSON from its own origin. The page renders Markdown to hiccup and never inserts raw HTML, and it sends a Content Security Policy.

## Super-agent

`bbh super serve` is the controller of [the super-agent design](docs/super-agent.md): threads, Workers, asks and replies in `~/.local/share/bbh/super/super.db`, SQLite opened through babashka's FFI (`src/bbh/sqlite.clj`, vendored from `babashka.sqlite`; `BBH_SQLITE_LIB` overrides the library path, else the Home profile's `libsqlite3.so.0`). Commands arrive on a loopback socket as EDN lines with a token, `<dir>/token` for you and one per Worker from `attach-worker!`, and each runs as one transaction keyed by its command id, so a repeated command returns its first result. The dispatcher drains the outbox into the Workers' mailboxes. `bbh super list` prints the threads by section (Stopped on you, Holding up work, Waiting on you, Needs attention, Sent, New, Working, Quiet, Archived), `bbh inbox` opens a terminal frame on the same list in the super-agent's Emacs daemon, `bbh-super` (the `bbh-emacs` Home service), as the `*bbh inbox*` buffer: in evil normal state, evil motions move, 1 to 4 pick an option, `s` sends, `A` approves, `r` notes, `m` marks read, `a` archives, `R` opens the Router, `gr` reloads, RET opens the thread's Worker, and SPC m lists them; SPC m r and SPC m i open the Router and the Inbox from any super-agent buffer, each alone in the frame; the controller reloads it through a local port the buffer registers, and the website's Inbox tab shows the sections and each thread with its asks as cards, picks saved on the phone until Send. `bbh.super.client` is the client side and loads no database. `start-worker!` gives a thread a Worker: a jj workspace of the repository under `super/workspaces/<thread>-<generation>`, a `wf` run there, and a buffer in that Emacs daemon, `T<n> <title>`, running `bbh acp --id super-W<n>` in agent-shell with `BBH_SUPER_TOKEN` and `WF_RUN` in its environment; the brief arrives as the Worker's first mailbox message, which the buffer runs as a prompt. `bbh super start-router` opens the Router (`router`, sandboxed by `BBH_ROLE=router`) and the Inbox there. A daemon that is not running is an error, never a fallback. `~/.config/bbh/policy.edn` (`desktop/configs/bbh/policy.edn`) sets the Worker cap and the resource locks; a Worker past the cap or waiting for a lock is queued and starts when `finish-worker!` frees it. In a Worker's REPL, `super-ask!`, `super-answered!`, `super-note!`, `super-milestone!`, `super-done!` and `super-failed!` talk to the controller. A `:done` finish lands the Worker's revision first (`bbh.super/land!`): its commits that the line lacks are rebased onto the line's tip, where the line is `::default@-`, your committed history, plus the revisions of finished Workers, so their work forms one line. Your working-copy change is never moved and not even snapshotted. Uncommitted changes in the Worker's working copy, conflicts the rebase leaves in its commits, or a line with two heads refuse the finish with the reason, and the Worker stays running to fix it or ask; `(super-done! {:onto rev})` names the tip. `bbh super thread! '{:title "..."}'` and the other commands in `bbh super help` call it from the shell; `bbh.super/call!` from a REPL. `bbh-web.scm` runs it as the `bbh-super` Home service.

## Lifetimes

The transcript is durable and only grows. The context is the transcript, so each request starts with the previous one and the prompt cache keeps hitting. A tool result longer than `:max-output-chars` keeps its head and tail, and the full text is saved under `outputs/` with its path on the result's first line. A reply that stops at the output limit is flagged: a cut-off tool call does not run and tells the model to split the work, and cut-off text gets a note to the user. A long run of tool calls without text gets a reminder appended to a tool result as it is recorded, never inserted later, asking for a status sentence in the same reply as the next call. If the model answers it with text only, bbh asks once whether the task is finished, so a status line cannot end unfinished work. bbh never compacts, and Claude Code runs with `DISABLE_AUTO_COMPACT=1`. When a request's input reaches `:rollover-at-tokens` (350,000, or 80% of a smaller known window), or the provider reports an overflow, bbh rolls over: it starts a new conversation whose first user message says this is a continuation and gives the old `messages.jsonl` path, so the model reads only what it needs, when it needs it. Between turns that message heads the user's prompt. Mid turn it tells the model to carry on, and Claude Code is restarted once its tool calls are answered, which drops any request then in flight, so that request's cost goes unreported. The REPL keeps running across a rollover. The old directory's `continued-in` names the new id, and `--resume` and `/resume` follow it, so a stored id such as herdr's still lands on the newest conversation. The REPL's live state lasts until the process restarts, which happens on a deadline, on an interrupted evaluation, on `/restart` and on resume. The session tells the REPL each evaluation's deadline, and `sh` stops 10 s before it, so a slow command returns `{:timeout true :job id}` and keeps running as a job while the REPL survives. `job-wait` instead moves the deadline: it writes the instant it needs to `deadline` in the REPL's directory, and the session waits that long, up to 10 min past the deadline, so one wait covers a long job without a model request per minute. `p/shell`, http and `Thread/sleep` have no such budget. `sh` commands run detached, so an interrupt stops the ones in flight by hand. There is no rollback and no branching. bb cannot interrupt a busy thread, so bbh kills the REPL process tree instead.

## Checks

[The evaluation guide](eval/README.md) covers two kinds of checks. The trace tools assert, render, and label every conversation, and score an LLM judge against your labels. The paired Pi evaluation compares live task completion and scripted protocol behavior. [Its baseline report](eval/RESULTS.md) includes the observed UI tradeoffs.

```sh
bb test                  # sessions, streaming, ACP process, Claude adapter, MCP and workflows, in parallel (BBH_TEST_JOBS)
bb test:emacs            # composer, history, cancellation and nREPL endpoint
bb test:doom             # installed Doom startup and Ctrl-N, in a separate terminal
bb test:doom launcher    # installed emacs-bbh launcher, including automatic session startup
bb try MODEL PROMPT...   # real prompts through agent-shell, printing the buffer
bb runs:html [N] [OUT]   # HTML page of the newest N conversations with prompts, tool calls, workflow journals, evidence and reviews; `make bbh-runs` from the repo root
bb runs:metrics [ROOT...]  # latency, tokens, cache share, cost and failures per day and model
bb stats                 # timeouts, restarts and errors per conversation, live workflow runs, review times
bb eval:lint             # assertion hits per cohort over real and eval conversations
bb eval:review           # label conversations good or bad with a critique
```

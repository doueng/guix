# bbh

A coding agent harness in babashka. Each conversation gets its own bb REPL process, and the model's single tool, `clojure`, evaluates code in that REPL. The model owns the REPL. It can require anything bb ships, keep helpers in memory for the whole conversation, and change its own system prompt and tool limits by redefining `bbh.env/manifest`.

## Front ends

From a project directory, `emacs-bbh` opens your normal Doom Emacs with a bbh session ready. It accepts normal Emacs arguments, including `emacs-bbh -nw` for terminal mode. The installed Emacs build is currently terminal-only.

In Emacs, `M-x eng/bbh` starts a session in [agent-shell](https://github.com/xenodium/agent-shell), which runs `bbh acp` and speaks the Agent Client Protocol over stdio. `M-x eng/bbh-cider-connect` attaches CIDER to that session's REPL. The Doom side lives in `desktop/configs/doom/agent-shell.el`.

The Emacs layout has a transcript above a persistent composer. The composer keeps your draft during replies and cancellation.

| Key | Action |
| --- | --- |
| `C-n` | Send, or queue while the model is working. |
| `RET` | Insert a newline. |
| `C-c C-k` | Interrupt the current turn after confirmation. |
| `M-v`, `C-v` | Page the transcript without leaving the composer. |
| `M-p`, `M-n` | Browse prompt history. |
| `C-c C-p` | Peek at the last response. |

`M-x eng/bbh-layout` restores the split. The composer header shows whether `C-n` sends immediately or queues a prompt.

In a terminal:

```sh
bbh                                   # new conversation
bbh --resume last                     # continue the newest transcript in a fresh REPL
bbh --model openrouter/deepseek/deepseek-v4.1-flash
```

Both front ends share the commands `/help`, `/model`, `/port`, `/restart` and `/usage`. Ctrl-C, or agent-shell's interrupt, aborts the model's turn and kills the REPL when an evaluation is running.

## Models

Model ids are `PROVIDER/MODEL`, and `BBH_MODEL` overrides the default, `claude/haiku`.

- `claude/...` runs Claude Code on the Claude subscription. Claude Code owns the agent loop and calls the `clojure` tool through `bbh.mcp`, an MCP server that forwards each call to the session over a loopback socket. Its rate-limit events feed `/usage`.
- `openrouter/...` runs bbh's own loop against OpenRouter, with the key from `OPENROUTER_API_KEY` or Pi's `auth.json`.
- `script/PATH` replays an EDN file of replies, for tests.

Switching models mid-conversation keeps the transcript. A Claude Code session that starts late receives the earlier transcript with the next prompt.

## The env

`desktop/configs/bbh/init.clj` is stowed to `~/.config/bbh/init.clj` and loaded into every new REPL. It defines:

- `bbh.env/manifest`, read before every model request: the system prompt, the skill index and the project's `AGENTS.md`.
- `bbh.env/expand-prompt`, which rewrites user input. It expands `/skill:NAME`, starts new conversations in poteto-mode, and answers `/poteto-mode-off` without the model.
- `bbh.env/commands`, which agent-shell offers for completion.
- `jev-classify-task`, the TypeSafe Jev router poteto-mode calls, plus the `sh`, `read-lines` and `edit!` helpers.

Skills come from Pi's directories, so the pstack pack and project skills load as they do in Pi.

## Layout

- `src/bbh/session.clj` owns a conversation: transcript, REPL, commands, events and the bbh tool loop.
- `src/bbh/claude.clj` and `src/bbh/mcp.clj` run turns through Claude Code.
- `src/bbh/acp.clj` translates events into ACP updates. `src/bbh/main.clj` is the terminal front end and the entry point.
- `src/bbh/repl.clj` starts the REPL process and evaluates over nREPL. `src/bbh/llm.clj` streams OpenRouter.
- `src/bbh/usage.clj` reports Claude, Codex and OpenRouter limits, after Pi's `pi-usage` extension.

Conversations live in `~/.local/share/bbh/conversations/<id>/` as `messages.jsonl`, `repl.log`, `repl.port` and, for Claude, `claude-session` and `claude.log`.

## Lifetimes

The transcript is durable and only grows. The REPL's live state lasts until the process restarts, which happens on a deadline, on an interrupted evaluation, on `/restart` and on resume. There is no rollback and no branching. bb cannot interrupt a busy thread, so bbh kills the REPL process tree instead.

## Checks

[The evaluation guide](eval/README.md) covers two kinds of checks. The trace tools assert, render, and label every conversation, and score an LLM judge against your labels. The paired Pi evaluation compares live task completion and scripted protocol behavior. [Its baseline report](eval/RESULTS.md) includes the observed UI tradeoffs.

```sh
bb test                  # sessions, streaming, ACP process, Claude adapter and MCP
bb test:emacs            # composer, history, cancellation and nREPL endpoint
bb test:doom             # installed Doom startup and Ctrl-N, in a separate terminal
bb test:doom launcher    # installed emacs-bbh launcher, including automatic session startup
bb try MODEL PROMPT...   # real prompts through agent-shell, printing the buffer
bb eval:lint             # assertion hits per cohort over real and eval conversations
bb eval:review           # label conversations good or bad with a critique
```

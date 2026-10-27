# bbh

A coding agent harness in babashka. Each conversation gets its own bb REPL process, and the model's single tool, `clojure`, evaluates code in that REPL. The model owns the REPL. It can require anything bb ships, keep helpers in memory for the whole conversation, and change its own system prompt and tool limits by redefining `bbh.env/manifest`.

## Front ends

From a project directory, `emacs-bbh` opens your normal Doom Emacs with a bbh session ready. It accepts normal Emacs arguments, including `emacs-bbh -nw` for terminal mode. The installed Emacs build is currently terminal-only.

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

`M-x eng/bbh-layout` restores the split. The composer header shows whether `C-n` sends immediately or queues a prompt. Sending `/new` works as it does in Pi. It ends the session, starts a fresh one in the same windows, and clears the transcript. `/resume ID` and `/resume last` clear the transcript too, unless no saved conversation has that id.

In a terminal:

```sh
bbh                                   # new conversation
bbh --resume last                     # continue the newest transcript in a fresh REPL
bbh --model openrouter/deepseek/deepseek-v4.1-flash
```

Both front ends share the commands `/compact`, `/help`, `/model`, `/port`, `/restart`, `/resume` and `/usage`. `/resume` lists the ten newest other conversations, and `/resume ID` or `/resume last` moves the session to one of them in a fresh REPL. The REPL's port file stays in the directory the session started in, so `eng/bbh-cider-connect` keeps working. A `/word` that neither bbh nor the env handles is an error rather than a prompt, and `/help` lists both kinds of command. Ctrl-C, or agent-shell's interrupt, aborts the model's turn and kills the REPL when an evaluation is running.

## Models

Model ids are `PROVIDER/MODEL`, and `BBH_MODEL` overrides the default, `claude/haiku`.

- `claude/...` runs Claude Code on the Claude subscription with `--effort` from the manifest. Claude Code owns the agent loop, its prompt caching and its output limit, and calls the `clojure` tool through `bbh.mcp`, an MCP server that forwards each call to the session over a loopback socket. Its rate-limit events feed `/usage`.
- `openrouter/...` runs bbh's own loop against OpenRouter, with the key from `OPENROUTER_API_KEY` or Pi's `auth.json`. Each request sends the conversation id as `session_id` for sticky routing, `reasoning.effort` mapped to the nearest level the model supports, and `max_tokens` up to 128,000. Anthropic models get OpenRouter's automatic `cache_control` breakpoint, and their reasoning details are sent back unchanged across tool calls. Rate limits, server errors and failed connections are retried five times with backoff, honoring `Retry-After`.
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
- `src/bbh/usage.clj` reports Claude, Codex and OpenRouter limits, after Pi's `pi-usage` extension.

Conversations live in `~/.local/share/bbh/conversations/<id>/` as `messages.jsonl`, `compactions.jsonl`, `outputs/`, `repl.log`, `repl.port` and, for Claude, `claude-session` and `claude.log`.

## Lifetimes

The transcript is durable and only grows. The model sees a context derived from it in `src/bbh/context.clj`. Between compactions the context only grows at the end, so each request starts with the previous one and the prompt cache keeps hitting. A tool result longer than `:max-output-chars` keeps its head and tail, and the full text is saved under `outputs/` with its path on the result's first line. A reply that stops at the output limit is flagged: a cut-off tool call does not run and tells the model to split the work, and cut-off text gets a note to the user. A compaction cuts the tool output of turns that were already over to `:trim-tool-chars`. A long run of tool calls without text gets a reminder to update the user appended to a tool result as it is recorded, never inserted later. When the context passes `:compact-at-tokens`, or the provider reports an overflow, the OpenRouter loop asks the model to summarize everything except the last `:keep-recent-tokens` and appends the summary to `compactions.jsonl`. `/compact TEXT` does the same on demand and tells the summary what to keep. All three limits are manifest keys. Claude Code compacts its own context, but a Claude Code session that joins late receives the compacted history. The REPL's live state lasts until the process restarts, which happens on a deadline, on an interrupted evaluation, on `/restart` and on resume. There is no rollback and no branching. bb cannot interrupt a busy thread, so bbh kills the REPL process tree instead.

## Checks

[The evaluation guide](eval/README.md) covers two kinds of checks. The trace tools assert, render, and label every conversation, and score an LLM judge against your labels. The paired Pi evaluation compares live task completion and scripted protocol behavior. [Its baseline report](eval/RESULTS.md) includes the observed UI tradeoffs.

```sh
bb test                  # sessions, streaming, ACP process, Claude adapter, MCP and workflows
bb test:emacs            # composer, history, cancellation and nREPL endpoint
bb test:doom             # installed Doom startup and Ctrl-N, in a separate terminal
bb test:doom launcher    # installed emacs-bbh launcher, including automatic session startup
bb try MODEL PROMPT...   # real prompts through agent-shell, printing the buffer
bb stats                 # timeouts, restarts and errors per conversation, live workflow runs, review times
bb eval:lint             # assertion hits per cohort over real and eval conversations
bb eval:review           # label conversations good or bad with a critique
```

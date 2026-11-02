# bbh super-agent: design

Status: proposal, nothing built. Written 2026-11-02.

This document describes how bbh could grow from separate conversations into one agent that you talk to from Herdr, Emacs and the iPhone, by typing or by voice, that works on several tasks at once, and that reacts to events such as GitHub comments. It adapts the Super-agent architecture from DeepSeek Harness (a Router, isolated Workers, event triggers, asynchronous tools, a watchdog) and the attention model of [Plannotator Inbox](https://github.com/backnotprop/plannotator#plannotator-inbox) (threads grouped by what they need from you, replies that wake the session that asked).

The shortest description is a small durable workflow controller with agents attached. The controller owns truth: work identity, events, lifecycle, delivery, permissions and resource ownership. The Router owns interpretation. Workers own execution. The surfaces own attention. Prompts never enforce correctness; code does.

## 1. Problem

Today every bbh conversation is its own island. You start one per task, usually in a Herdr pane, and you are the scheduler.

- You remember which pane holds which task. `herdr-agent-pick` (Ctrl+b a, Alt+Space a) and the attention-sorted agent panel help, but they list sessions, not work.
- A question an agent asks sits in its pane until you happen to look. From the phone you can only see sessions that have an Emacs front end.
- Nothing starts without you. A review comment on a PR waits until you open a session and paste it in.
- Long commands block a turn (`job-wait`), and what you type meanwhile queues until the turn ends.

Human attention, not model ability, limits how many tasks run at once. In practice you can follow 3 to 5 live sessions.

## 2. Goals and non-goals

Goals
- One agent to talk to. You never pick a session to give an instruction.
- Work is tracked as threads (a task, a PR, a topic), with one short list of what needs you.
- An agent's question reaches you on whichever surface you are on, and your answer wakes the session that asked without that session waiting idle.
- Several tasks run in parallel without mixing their contexts.
- Configured events (GitHub, CI, job exits) start or continue work without you.
- Everything keeps working across REPL restarts, rollovers and a laptop sleep. Work resumes when the laptop wakes.

Non-goals for the first version
- An always-on cloud host. The laptop is the only machine. Section 11 covers moving the Router later.
- Team use or other people's messages. Every trigger source is yours or allowlisted.
- Replacing Herdr, Emacs or the phone page. The design adds views to them.
- Compaction. bbh keeps its rollover model.
- Several Workers on one thread. A thread has one active Worker at a time (section 4).

Automated triggers (GitHub, CI, speculation) are gated on four properties being in place first: durable delivery, execution isolation, capabilities in code, and clear ownership. The manual thread and ask prototype needs none of them.

## 3. What already exists

| Need | Existing piece |
|---|---|
| Long-lived conversation | bbh never compacts. It rolls over at 350k tokens with a pointer to the old `messages.jsonl`. |
| Isolated execution sessions | bbh conversations in Herdr panes, driven by `herdr agent start/prompt/wait/read/list`, with working, idle, blocked and done states. |
| Cheap routing | `jev-classify-task` picks a route, and the route table picks a pstack workflow. |
| Structured task state | `wf` runs. Gates run in the runner, a fresh reviewer records verdicts, and `wf status` reads active, done or blocked. |
| Delivery into a session | `bbh.inbox`, a `mailbox/` directory per conversation with at-least-once delivery (ack, retry, busy). |
| Phone surface | `bbh web`, Scittle and Replicant, on `127.0.0.1:8766`, published with `tailscale serve` and limited to the node owner. |
| Background work | `job`, `job-status` and `jobs`, file-backed and surviving REPL restarts. |
| Recoverable state | Conversations, runs, jobs and workflow journals are files. `checkpoint.clj` (in progress) replays definitions after a REPL kill. |
| Spill with a pointer | Large tool output goes to `outputs/` with a `read-lines` pointer. |
| Voice | Hex, local dictation on the Apple GPU. Holding Cmd+M pastes the transcript at the cursor, and `hex dictation stop --print` prints it instead. Hex also keeps a vocabulary list. |

The design mostly connects these. The new parts are the thread store, the asks protocol, a small controller, the Router's restricted environment, a voice channel, and three views.

## 4. Concepts

Three identities are kept apart.

| Concept | Example | Purpose |
|---|---|---|
| Origin | `:herdr`, `:emacs`, `:phone`, `:voice`, `:gh`, `:ci`, `:job` | Where an event arrived. Never used for routing. |
| Resource key | `gh:engstrand/guix#41`, `ci:main`, `job:1234` | The external object an event concerns. Used for pass-through. |
| Thread id | `T12` | The bbh work stream that owns the resource. |

**Thread.** A unit of work you care about, such as "fix the keybinding check" or PR `engstrand/guix#41`. It has a short handle `T<n>` that every surface shows and that you can type or say anywhere. A thread links to resource keys and lives as long as the work matters, which is longer than any one Worker.

**Event.** An accepted fact with a global sequence number: your message, a GitHub comment, a CI result, a job exit, an ask, a reply, a Worker or `wf` state change. Events enter only through the controller (section 6).

**Worker.** A durable execution record: conversation id, `wf` run, jj workspace path, process identity, model, current job, granted capabilities and an ownership lease. A thread has at most one active Worker. When work arrives after a Worker has finished, the thread gets a new Worker generation that inherits the thread's history. A Herdr pane is a view of a Worker, not its identity, so a Worker can also run headless.

**Router.** One long-lived bbh conversation that reads your messages and the events no thread owns. It replies to you, starts Workers and relays the rare decision that crosses threads. It has no shell, and what it can start is limited by capabilities, not by its prompt.

**Ask.** Something a Worker needs from you: a `question` with options, an `action` only you can do, or a `review` of an immutable result. Each option has a stable id. `:stopped true` means the Worker cannot continue, `:holds-up "T14"` means another thread waits.

**Reply.** Your disposition of one or more asks on a thread: picks and notes, or an explicit dismissal. Replies are batched into one message per Worker and carry a stable reply id, so a repeated Send is one logical reply.

**Lease.** Who may send instructions to a Worker right now: the Router, or you after `takeover!`. Typing in a pane never changes the lease by itself.

## 5. Data model

### Store: SQLite

Authoritative orchestration state lives in `~/.local/share/bbh/super/super.db`, SQLite in WAL mode with `synchronous=FULL`, `foreign_keys=ON` and a 5 s busy timeout. The controller is the only writer. Views open read-only connections, so the inbox pane, the phone page and Emacs query indexed tables instead of folding history, and they read while the controller writes.

```
super.db
  events          append-only history, global seq
  commands        idempotency keys with the result each returned
  threads, workers, asks, replies, resource_locks   materialized state
  outbox          deliveries owed, with acked and processed marks
  schema_version  PRAGMA user_version, migrated before the controller starts
```

An accepted command is one transaction: look up the command id and, if seen, return the original result; check the caller's identity and the expected state version; insert the event; update the materialized rows; insert any outbox rows; commit. A failed check rolls everything back. The duplicate check comes before the version check, so a retried Send returns its original success rather than a conflict.

State transitions stay pure Clojure functions over events. The tables are their materialization, and a test replays the whole `events` table into an empty database and compares it with the materialized state. Nothing above the store knows SQL.

Delivery is at least once. The dispatcher drains `outbox` into the existing bbh mailboxes and marks a row acked when the mailbox poller says so, and processed when the Worker's turn records the reply id. Receivers drop a message id they have already processed. A model turn, a shell command or a GitHub push cannot join the transaction, so each external side effect carries its own idempotency key and a reconciliation rule.

Binding: `babashka.sqlite`, which calls `libsqlite3` through `babashka.ffi`. bb 1.13.226 here has the FFI namespace, and Guix's `sqlite` package supplies the library; the library is experimental, so its git revision is pinned. Fallbacks are the `org.babashka/go-sqlite3` pod, whose aarch64 Linux build is unverified, and the `sqlite3` CLI with one SQL script per transaction. Backups use `.backup` or the backup API, never a copy of the file while WAL is active. Conversations, `wf` journals and job records keep their file formats.

### Records

```clojure
;; thread
{:id "T12" :title "Alt+W closes windows everywhere"
 :resources #{"gh:engstrand/guix#41"}
 :worker "W7"                        ; active generation, or nil
 :history ["W3"]                     ; earlier generations
 :execution :waiting                 ; see dimensions below
 :resolved-at nil}

;; worker
{:id "W7" :thread "T12" :generation 2
 :conversation "20261102-114641-934" :wf-run "20261102-114816-571-change"
 :workspace "~/.local/share/bbh/super/workspaces/T12-2"
 :pane "w1:p3"                       ; view, may be nil
 :pid 4312 :model "claude/opus"
 :capabilities #{:read :edit-workspace :run-checks}
 :lease :router :state :waiting}

;; ask
{:id "a1" :thread "T12" :worker "W7" :seq 5120 :kind :question
 :text "Keep Ctrl+Alt+W as an alias?"
 :options [{:id "no" :label "No"} {:id "week" :label "Yes, for a week"}]
 :recommended "no" :stopped true :state :open}

;; reply (a command, then an event)
{:command-id "c-9f2" :thread "T12" :asks-version 5120
 :picks {"a1" "no"} :note "" :dismiss #{}}
```

Picks, drafts and read cursors are per client, not in the thread record. A client's Send carries `:asks-version`, the `seq` of the asks it saw. If another device answered since, the controller rejects the Send with the current state and the client shows the conflict rather than creating a second reply.

### Ask lifecycle

```
open ──pick (client only)──> picked ──send──> sent ──mailbox ack──> delivered ──worker turn──> answered
 │                                                                                        
 ├──dismiss (mark read / skip)──> dismissed   (delivered to the Worker as "do not wait", never as an answer)
 └──newer ask supersedes──────> superseded  (shown as replaced)
```

Every terminal state is recorded and delivered. Dismissed never means approved. A Worker that cannot continue after a dismissal asks again or fails its `wf` phase.

### Worker lifecycle

```
starting ─> working ─> waiting (open stopped ask) ─> working ...
   │           │
   │           ├─> queued (no execution slot)      ├─> failed (wf phase exhausted, REPL dead)
   │           └─> done (wf done)                  └─> taken-over (lease held by you)
```

### Three dimensions, one derived section

Execution state, attention state and read state are independent facts. The section is derived from them and never stored.

| Dimension | Values |
|---|---|
| Execution (from the Worker) | queued, working, waiting, done, failed, none |
| Attention (from open asks) | stopped, holds-up, waiting, review, none |
| Read (per client) | unseen events after the client's cursor, unsent picks or draft |

| Section | Rule, first match wins |
|---|---|
| Stopped on you | attention stopped |
| Holding up work | attention holds-up |
| Waiting on you | attention waiting or review |
| Needs attention | execution failed with no open ask. A technical blockage, not a decision. |
| Sent | a reply is sent or delivered but not yet answered |
| New | unseen milestone events |
| Working | execution working or queued |
| Quiet | nothing above and not resolved |
| Archived | resolved. A new event shows it as "came back" without unresolving. |

The badge counts the first three. Progress notes update the thread's summary line without making it New; only milestones (a `wf` phase change, a push, an ask) do. The list order holds while you look, and new rows wait behind an "N new" line.

## 6. Architecture

```
 producers                 bbh super (controller)                        consumers
 ---------                 ----------------------                        ---------
 phone page   --\          command socket (loopback)                    Router conversation
 Herdr, Emacs ---+-------> validate, dedupe by command id, authorize     Worker conversations
 voice (bbh say) /            |                                           (bbh mailboxes)
 gh / ci pollers             commit: event + fold + outbox   ---------->  dispatcher drains the
 job exits, wf journal        |                                           outbox, retries until acked
 watchdog (health check)   super.db (SQLite, WAL)                        views read it read-only
```

**Controller.** `bbh super`, a Guix Home Shepherd service like `bbh-web`. It listens on a loopback socket for commands, each with a stable `command-id`. A loopback socket does not identify its caller, so every command carries a token the controller issued: one for you (the surfaces), one for the Router, and one per Worker, handed to the Worker's environment at start and revoked when it ends. Capabilities are bound to the token, not to the sender's claim. A command is deduplicated, authenticated, authorized, then committed. It holds no model and no conversation. Restarting it is safe at any point, because every effect it owes is in the outbox.

**Dispatcher.** Part of the controller. It drains `outbox` into the existing bbh mailboxes as section 5 describes. The mailbox stays the transport.

**Commands.** The whole API is a dozen commands, callable from a REPL helper, the CLI (`bbh super <command>`) or the socket.

```clojure
(thread! "Improve bbh web layout")                       ; => "T12"
(link! "T12" "gh:engstrand/guix#41")
(start-worker! "T12" {:workflow :change :brief ... :capabilities ...})
(ask! {:kind :question :text ... :options [...] :stopped true})
(note! "progress text")                                    ; summary only
(milestone! "pushed 3 commits")                           ; makes the thread New
(reply! "T12" {:asks-version 5120 :picks {...} :note ... :dismiss #{}})
(steer! "W7" "also handle the phone layout")
(cancel! "W7")
(takeover! "W7") (handback! "W7")
(approve! "T12" {:revision "c068de11" :evidence-sha ...})
```

**Fast path.** `policy.edn` names each origin's trust and what it may start. Events from unlisted authors are stored and shown to you but never reach a model with tools. Events with an external id (GitHub delivery id, CI run id) are deduplicated, and bursts on one resource key are coalesced before the Router sees them. The Router's pending queue is bounded; past the bound, new candidates wait as events, not as prompts.

**Routing.** Deterministic first. A handle in the text (`T12`, spoken "tee twelve") or a resource key already linked to a thread resolves without a model. Only unresolved candidates reach the Router, and `jev-classify-task` runs before a Router turn so noise costs a Jev call. The Router sees linked events in its batch summary but does not act on them.

**Router environment.** A pinned bbh conversation whose manifest exposes only the commands above plus read functions (`threads`, `thread`, `events`). Reads inline the ids of related objects so the Router can follow them hop by hop. Its mailbox is drained in batches ordered by `seq`. The Router has delegation authority, not execution authority: `start-worker!` names the task and its origin, and the controller sets the Worker's capabilities from the authenticated origin and `policy.edn`. So a Router with no shell can start a shell-capable Worker for your direct request, and cannot talk a GitHub-originated task into one.

**Workers.** Ordinary bbh conversations running a pstack workflow, with these additions.

- `start-worker!` creates a jj workspace per Worker (`jj workspace add`), so two Workers on the same repository never share a working copy, a `@` revision or generated files. The main checkout stays yours. The Worker's `wf` evidence directory is its own already.
- Named resource locks in `policy.edn`, such as `guix-build`, `repo-push` and `guix-system`. A Worker that needs one waits in `queued` with the lock named in its summary. `guix-system` is never granted to a Worker; a system switch is always an `action` ask to you.
- A concurrency cap, three Workers at first. A fourth is `queued`, not working.
- A Worker runs headless by default. Attaching a Herdr pane is a view (`herdr pane` running `bbh --resume`), so speculative Workers do not clutter workspaces.

**Three kinds of input to a busy Worker.** They have different semantics and are different commands.

| Command | When it arrives | Mechanism |
|---|---|---|
| `reply!` | After the current turn | Mailbox, as today. |
| `steer!` | At the next safe step | Appended to the next tool result, or prepended to the next model request if no tool runs first. Best effort within a turn, guaranteed by the next turn. |
| `cancel!` | Now | The controller kills the Worker's running job or REPL evaluation through bbh's interrupt, then delivers the reason. Never depends on the model cooperating. |

**Jobs.** `job` reports exit through the controller, so a Worker can start long work, end its turn and be woken with the result. Job records survive a controller restart, and recovery rediscovers a running job by pid and start time rather than launching it again.

**Watchdog and recovery.** Deterministic, with a model only when an anomaly needs interpretation.

- Shepherd supervises the controller. The controller writes a heartbeat row.
- A health check every few minutes compares desired state (Worker records) with reality: process alive, REPL answering, `wf` journal advancing, job state, oldest unacked outbox record, oldest open stopped ask. Silence alone is not an anomaly; a Worker waiting on you or holding a build lock is expected to be quiet.
- Each Worker ends up classified as running, waiting, recoverable, done or needs-human. Recoverable Workers are restarted with a bounded policy (three tries, then `failed`), which puts the thread under Needs attention.
- After a sleep, reconciliation checks what survived before restarting anything. While the laptop sleeps nothing runs: no Router, no pollers, no page, no new pushes. Section 11 is the only way past that.
- Only an unexplained anomaly starts a short model session, which can read the records and raise an `action` ask on the Watchdog thread.

**Full duplex, in short.** Your input never waits on a long tool call, because long work is a job and messages have the three delivery kinds above.

## 7. Surfaces

All three surfaces show the same threads, sections and handles. They differ in what they are good at. Herdr is the main desk, because that is where the Workers already run and where you spend the day.

### Herdr: the main desk

The first Herdr workspace is **Inbox**, pinned to position 1 so Ctrl+1 (Cmd+1) always reaches it. Its first tab has two panes.

- **Inbox pane (left, about 40%).** `bbh inbox`, a live list of threads grouped by section, with the badge count in the pane title. It is built like `herdr-agent-pick`, on fzf with `--listen`, so the controller pushes a reload whenever an event changes a thread and the list stays current without polling. The order holds while you look, and new activity waits behind an "N new" line.
- **Router pane (right).** The Router conversation in bbh's terminal front end. Typing here talks to the Router, as the Emacs composer does.

Keys in the inbox pane:

| Key | Action |
|---|---|
| j, k | Move between threads. The preview shows the open ask with numbered options, or the Worker's screen when nothing is open. |
| 1 to 4 | Pick an option on the selected thread's ask. The pick is saved at once. |
| r | Write a note on the selected thread, in a small popup. |
| s | Send every pick and note on the selected thread, as one message per Worker. |
| Enter | Focus the Worker's pane, attaching one first if it runs headless. |
| e | Open the thread in Emacs, for a diff review or a long reply. |
| m | Mark read, which closes the asks and tells the Worker not to wait. |
| a | Archive. Open asks become dismissed, which the Worker is told. |

Workers run in panes in their project's workspace. The pane name starts with the thread handle (`T12 keybindings`). Herdr's agent panel still shows their states.

- **Alt+Space t focuses the inbox pane** from anywhere in Herdr, and Cmd+Space t does the same from any window by focusing the Herdr terminal first. Alt+Space a and Ctrl+b a keep the agent picker until threads are the normal entry point, then a can take over.
- **Toasts.** Only Stopped and Waiting asks raise a Herdr system toast. Progress never does.
- **Taking over.** `t` in the inbox pane, or `/takeover` in a Worker's pane, runs `takeover!`, which moves the Worker's lease to you. The Router stops relaying to it until `/handback`. Typing in a pane without the lease still works, but is a plain prompt and does not change who the Router thinks is steering.

### Emacs: long-form review and writing

Emacs is where you go from the inbox when a thread needs more than a pick.

- **Thread view** (`e` in the inbox pane, or SPC o i). The thread's events in an org buffer, with asks as checkbox lists. `C-c C-c` on an option picks it, text under the ask adds a note, and `C-c C-s` sends. A `review` ask opens the diff with `jj diff` in a split, and comments on lines are added to the reply.
- **Inbox buffer.** The same sections as org headings, for when you are already in Emacs.
- **Router composer.** The existing bbh layout can be pinned to the Router conversation, so long instructions can be written with the Emacs editor.
- **Worker transcript.** One key opens a Worker's own transcript in agent-shell, for when you want to steer the details yourself.

### iPhone: triage and answers away from the desk

The `bbh web` page gains an Inbox view next to the session tabs. It stays loopback-only behind `tailscale serve` and the owner check.

- **Inbox list.** Sections and badge as in section 5. A row shows handle, title, the open ask's first line and age. Nothing updates while the laptop sleeps; the page says when its data is older than a few minutes.
- **Thread view.** Events as a chat. Asks show as cards with large option buttons and the recommended option marked. A tap saves the pick at once, and Send at the bottom sends every pick and note on the thread. Archive and "Mark read" (close the asks and tell the Worker not to wait) sit in the header.
- **Talk to the Router.** A composer at the bottom of the Inbox list sends to the Router. Typing a handle first ("T12 ...") goes to that thread.
- **Notifications.** If you add the page to the home screen, iOS 16.4 and later can receive Web Push. Push is sent only for Stopped and Waiting asks, one per ask, and a newer push for the same thread replaces the older one. Lock-screen answer buttons are not assumed, because Safari's web push support for notification actions is unverified. A tap opens the ask.
- **Review on the phone.** A review card shows the summary, the gates that passed and the diff stat. Approving is allowed and names the exact revision. Line comments are left for Emacs.
- **Hardening.** External text (GitHub comments, CI logs) is rendered as text, never as HTML or Markdown links. Mutations carry the command id and the owner's Tailscale identity, requests are size-limited, and the page refuses cross-origin POSTs as `bbh web` does today.

### Voice: talking to the agent

Dictation is a way of talking to the agent, not only a way of typing into a window. It works the same from every surface and arrives as an ordinary event with `:channel :voice` and the transcript, so the Router and Workers know the text was spoken and may contain recognition errors.

- **At the desk, hold to talk to the agent.** Holding Cmd+Shift+M runs `hex dictation start`. Releasing it runs `hex dictation stop --print` and hands the transcript to `bbh say`, which appends a voice event. Nothing is pasted, so it works whatever window has focus. Cmd+Shift+M is free in Hyprland today, but kanata and the application layers still need checking with the keybindings skill.
- **Where it goes.** If the inbox pane has a thread selected and you spoke within a minute of selecting it, the voice event goes to that thread. A spoken handle ("T twelve, use plan B") goes to that thread. Anything else goes to the Router. Thread handles and project names are added to Hex's vocabulary so they are recognized, and the controller also accepts spoken forms such as "tee twelve".
- **Answering asks by voice.** "Option two", "the recommended one" or "yes, for a week" become picks on the selected thread's ask, shown in the inbox pane. A dictated reply is a draft until you say "send" or press s. Voice never approves an `action` ask on its own, such as `make switch`.
- **Plain Cmd+M stays as it is.** It pastes at the cursor, so you can still dictate a note in the inbox's `r` popup, the Router pane or a Worker's pane.
- **Spoken replies.** The Router answers in text. Reading short replies aloud is an open question (section 14).
- **On the iPhone.** The composer accepts iOS dictation from the keyboard's microphone key with no extra work. A Shortcut ("Tell bbh") that sends dictated text to the page over the tailnet would let you talk to the Router from the lock screen or Siri. Whether a Shortcut can pass the page's owner check is unverified.
- **Screen and voice together.** `hex annotate` records the screen with drawing and narration. Attaching such a recording to a thread ("this pane looks wrong") gives a Worker both the picture and what you said about it.

## 8. User journeys

### J1. Morning triage on the iPhone

1. At breakfast a push says "T14 stopped: which Herdr key for the thread picker?".
2. The tap opens the thread on the phone. The card offers "Alt+Space a (recommended)" and "Alt+Space t". You tap the first and press Send.
3. The controller commits the reply and an outbox record together, and the dispatcher writes one message to the Worker's mailbox. The thread shows Sent until the Worker acks, then the Worker, idle since it asked, starts a new turn with your answer. If the laptop had been asleep, the Send waits on the phone as a queued command until the page reaches it.
4. Back in the Inbox list, T9 shows under Waiting with a review. You read the summary (gates passed, 4 files), tap Approve, and T9 moves to Archived.
5. You type "T11 also handle the phone layout" in the Router composer. The handle routes it without a model, as a `steer!` to T11's Worker. The Router sees it in its batch summary and does nothing.

### J2. Start a task at the desk

1. You press Ctrl+1 to reach the Inbox workspace and type in the Router pane "Make the bbh web page show cost per session".
2. The Router calls Jev, gets `feature`, creates T15 and calls `start-worker!` with the `change` workflow. A Herdr pane `T15 web cost` opens in the guix workspace and bbh starts there with `wf start change ...`.
3. The Router replies in one line: "Started T15 (change workflow). I'll ask if it needs you."
4. T15 appears in the inbox pane under Working. You keep typing other requests. Nothing waits on T15.

### J3. A Worker needs a decision while you are busy

1. T15's Worker finds two ways to get the cost and calls `ask!` with `:stopped true` and a recommendation, then ends its turn.
2. T15 moves to the top of the inbox pane under Stopped on you. Herdr shows a toast, and the phone gets a push.
3. At the desk you press Alt+Space t, which lands on T15 in the inbox pane. The preview shows the question with options 1 and 2. You press 1, then s.
4. You could equally answer from the phone or Emacs. If both you and the phone answered, the second Send carries a stale `:asks-version` and shows "already answered: option 1" instead of sending. If both you and the phone answered, the second Send carries a stale `:asks-version` and shows "already answered: option 1" instead of sending. If you answered twice, on the phone and in Emacs, the second Send finds the ask closed and says so.

### J4. A review comment on a PR you pushed

1. The `gh` poller sees a comment on `engstrand/guix#41` from an allowlisted author.
2. The resource key `gh:engstrand/guix#41` is linked to T12, so the comment routes to T12 without the Router. T12's Worker finished earlier, so the controller starts a new Worker generation on T12 with the thread's history and the capabilities for a GitHub-originated task: read, edit its own workspace, run checks. No push.
3. The Worker fixes the nit in its workspace, runs the check, and raises a `review` ask with the revision and evidence.
4. You see T12 under Waiting in the evening, approve the exact revision, and the Worker pushes and posts a short PR comment. Later, once this has gone well for a while, `policy.edn` can grant push for trivial fixes on your own PRs.

### J5. Changing your mind in another channel

1. On Monday you told the Router in Emacs "T16 migrate to plan A".
2. On Tuesday, on the phone, you write "T16 actually plan B, A breaks the Asahi build".
3. The message goes to T16's Worker as a new turn. The Worker reads its `wf` evidence, sees it is in `implement`, and either adjusts or calls `ask!` if plan B changes the shape. If so, its `wf` run goes back to `shape`, which `wf` already supports.

### J6. Taking over and handing back

1. T15 is drifting. In the inbox pane you select T15 and press `t`, which runs `takeover!` and lands in its pane.
2. You type directly to the Worker for a few turns. The lease is yours, so the Router queues anything for T15 instead of relaying it.
3. You type `/handback` in the pane. The Router gets the queued items and resumes normal routing.

### J7. Silent fix in the background

1. CI fails on main overnight. `policy.edn` marks CI failures on main as candidates with the "silently reproduce" rule.
2. The Router starts a Worker on T17 with the `bug-fix` workflow and `--repro` set to the failing check, and marks the thread speculative. A speculative Worker gets the smallest capability set: its own jj workspace, checks, no push, no network beyond the model. A speculative Worker gets the smallest capability set: its own jj workspace, checks, no push, no network beyond the model.
3. Speculative threads stay in Quiet. If the `wf` run reaches `done`, with the reproduction failing first and passing after and an independent review, T17 moves to Waiting with a review ask. If the run blocks or gives up, the Worker's output is discarded, the thread is archived, and you were never interrupted.

### J8. The laptop sleeps

1. You close the lid during a run. Every process stops, and nothing happens until you open it: no pollers, no page, no pushes.
2. On wake the controller opens the database, SQLite recovers the WAL, and the dispatcher resumes unacked outbox rows. Herdr restores panes and their bbh conversations, as it does today.
3. The health check reconciles each Worker record with reality. A Worker whose REPL survived is left alone. One whose REPL died but whose `wf` journal is intact is restarted once with its journal. A running job is rediscovered by pid, not launched again. A Worker that fails three restarts goes to Needs attention.

### J9. Sign-off at the desk

1. T15 reaches `done`. Its `review` ask shows under Waiting.
2. In the inbox pane you select T15 and press e, which opens the thread view in Emacs. The diff opens beside the thread with the `wf` evidence links. You comment on two lines and press `C-c C-s`.
3. The Worker gets both comments in one turn, fixes them and asks for review again, with a new revision. The earlier approval is stale by construction. You approve the new revision, the Worker commits with jj, and the thread is archived.

### J10. Talking to the agent at the desk

1. You are reading a PR in Chrome. Without switching windows you hold Cmd+Shift+M and say "start a task, make Alt Space a open the inbox pane, and check kanata for Cmd Shift M".
2. On release Hex prints the transcript and `bbh say` sends a command to the controller, which stores a voice event. Nothing was pasted into Chrome.
3. No handle was spoken and no thread was selected, so the Router gets it, starts T18 with the `change` workflow, and its one-line reply shows in the Router pane.
4. Later T18 stops with a question. You select it in the inbox pane, hold Cmd+Shift+M and say "the recommended one, send". The pick and the Send show in the pane, and the Worker wakes.

### J11. Talking to the agent away from the desk

1. Walking home you remember T16. You open the bbh page on the iPhone, tap the composer and use the keyboard's microphone: "T16, also keep the old plan as a fallback branch."
2. The handle sends it straight to T16's Worker. The event is marked as dictated, so the Worker treats an odd word as a likely recognition error rather than an instruction.
3. If the Shortcut from section 7 works, the same message could go through Siri without unlocking the phone.

## 9. pstack and poteto-mode

- The Router does poteto-mode steps 1 and 2 for you. It classifies, picks the workflow from the route table and starts the Worker with `wf start`. The Router itself is not a workflow run, the same as the manual route.
- One Worker is one `wf` run, which matches today's limit of one run per conversation.
- `wf` states drive the inbox. `blocked` is Stopped on you, a phase failure stays inside the Worker, `done` makes a review ask. A model's prose never decides that a task is done.
- Gates and the independent reviewer are what make speculative work (J7) safe to run unasked.
- `large-project` could later hand each stage of its plan to its own Worker, with the stage's check as that Worker's `--check`. Not in the first version.

## 10. Security

Trigger sources make untrusted text reach an agent on a laptop with private files in `local/`. Author allowlists help but are not a boundary: an allowed comment can quote an attacker's log, CI output can carry instructions, and an account can be compromised. So permissions are capabilities granted by the controller per task origin and enforced in code, and starting work is separate from publishing its effects.

| Task origin | Capabilities at first |
|---|---|
| Your direct instruction (Herdr, Emacs, phone, voice) | Today's bbh workflow permissions in the Worker's own jj workspace. Pushing and committing to the main checkout go through a `review` ask. |
| GitHub comment, allowlisted author | Read, edit own workspace, run checks. Publishing needs your approval of the revision. |
| CI failure | Reproduce and edit in own workspace. No push. |
| Speculative task | Own workspace, checks, no network beyond the model. |
| System change (`make switch`, services) | Never a capability. Always an `action` ask naming the exact command. |

- The controller, not the Router, decides a Worker's capabilities, from the authenticated origin of the request and `policy.edn`. A GitHub-originated task cannot become a Worker with push rights by the Router's choice.
- A jj workspace isolates working copies, not processes. A Worker is a bbh REPL with a shell under your user, so a capability it lacks is enforced by what the controller will accept from its token, by resource locks, and by the review gate before publishing. Filesystem and network restrictions for speculative Workers need real process isolation, such as a bubblewrap or Guix container around the Worker's REPL, and are part of phase 6, not assumed before it.
- Approval refers to an immutable artifact: a revision id and the hash of its `wf` evidence. A later change makes the approval stale, and `approve!` on a stale revision is rejected.
- `wf` gates and the independent reviewer establish that code works. They are not a security boundary and do not stand in for your authorization of a side effect.
- Voice input is yours, but a transcription error is still an instruction, so dictated text never completes an `action` ask.
- The phone page keeps loopback binding, `tailscale serve` (never `funnel`) and the owner-login check, plus the hardening in section 7. Web Push uses VAPID keys stored outside the repo.
- Credentials stay out of events and asks. The controller refuses commands whose payload matches the secret patterns `make lint` already uses.
- nREPL ports stay on loopback. A remote endpoint (section 11) needs tailnet ACLs first.

## 11. Later: an always-on Router

Moving the controller and the Router to an always-on host on the tailnet makes the agent reachable while the laptop sleeps. Workers that need the laptop's working copy then wait for it. On wake, the laptop's bb nREPL (already how bbh runs a REPL) serves as the endpoint that runs commands. This needs the security work in section 10 first and is out of scope here.

## 12. Cost and measurement

The DeepSeek ablation measured about 23M cache-read tokens per run for the two-level design against 6 to 11M for one session with subagents, on a synthetic load with simulated coding and five runs per design. Treat it as a direction, not a prediction for this laptop. Expect parallel Workers to use Claude subscription limits faster. Mitigations are deterministic routing, a Jev call before any Router turn, pass-through, and the concurrency cap.

Measure outcomes against today's manual Herdr workflow on a dozen real bbh tasks: time from request to Worker start, time spent waiting on you, time to done, routing mistakes, duplicate deliveries, recoveries, tokens, and the number of times you were interrupted. The goal is more finished work per unit of your attention, not more parallelism.

## 13. Rollout

The hard correctness properties come first, and the surfaces grow beside them. Each phase ends in a check you can run.

| Phase | Deliverable | Acceptance |
|---|---|---|
| 0 | Plannotator spike | A bbh Worker asks a question through Plannotator, you answer from its phone page over the tailnet, the answer arrives as a bbh turn. Decide build or integrate on what the bridge looks like. |
| 1 | Controller and store | `babashka.sqlite` opens Guix's `libsqlite3` on this machine. `bbh super` with `thread!`, `ask!`, `reply!`, the events, commands and outbox tables. Kill the controller at every step of a reply and restart: accepted events are kept, a retried command returns its original result, and replaying all events into an empty database reproduces the materialized state. |
| 2 | Ask and reply protocol | Picks, Send with `:asks-version`, dismiss, supersede, delivery states, `bbh super list` and one view (the Herdr inbox pane). Sending the same reply twice yields one logical reply and one Worker turn. Answering from two devices leaves one winner and one clear conflict. |
| 3 | Worker ownership and isolation | Worker records, generations, jj workspaces, resource locks, the concurrency cap, `takeover!` and `handback!`. Two Workers change the same repository without touching each other's working copy, evidence or revision. |
| 4 | Router and dispatch | Restricted env, handle and resource routing, batching. Requests from Herdr, Emacs and the phone resolve to the right thread, and a GitHub-origin task cannot get push rights. |
| 5 | Jobs and recovery | Job exit events, `steer!` and `cancel!`, health check, bounded restarts, sleep reconciliation. Kill the controller during a job and it is rediscovered, not relaunched. Steer a busy Worker, then cancel it, and the two behave differently and predictably. |
| 6 | Triggers, notifications, speculation, voice | `gh` poller, `policy.edn` capabilities, process isolation for speculative Workers, Web Push, J7, `bbh say`. A malicious instruction inside an allowlisted comment gains no capability and bypasses no approval, and a speculative Worker cannot reach the network or files outside its workspace. |

The first slice worth building is smaller than phase 1 and 2 together: the controller with one Worker per thread, `thread!`, `attach-worker!`, `ask!` and `reply!`, durable and testable without a model, plus `bbh super list` and the inbox pane. That alone gives the key experience: a Worker asks, stops spending turns, and resumes from an answer sent elsewhere. Automated triggers wait until phases 1, 3 and 4 are done.

## 14. Open questions

- Is Cmd+Shift+M free through kanata and every application layer, or does voice need another chord?
- Should short Router replies be spoken back when the request came by voice?
- Can a Siri Shortcut reach the page through `tailscale serve` and pass the owner check?
- Plannotator: phase 0 decides. If its thread and reply API can be bridged without a second ask lifecycle, bbh owns execution and workflow state and Plannotator owns ask and reply interaction, with a thin Emacs overview backed by bbh. If not, the inbox pane and phone view are built as described.
- Router model: deterministic routing and Jev first, a mid-priced model for the rest. Measure routing accuracy on real requests before choosing.
- iOS Web Push works from a home-screen page on iOS 16.4 and later with permission and a service worker. Lock-screen action buttons stay optional until tried on the phone.
- Memory: keep archived threads, their summaries, events and links to conversations and `wf` evidence indefinitely and searchable. No memory subsystem until that is a problem.

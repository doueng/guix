# bbh super-agent: design

Status: stages 0 through 5 implemented, including the Router sandbox, with offline integration coverage and a live Herdr pane replacement (before the move to Emacs on 2026-11-03, which was checked live with a Worker ask, pick, send and finish); suspend acceptance remains to be exercised. Stage 6 voice input and shortcut are implemented; recorded-clip acoustic acceptance remains pending. Written 2026-11-02.

This document describes how bbh could grow from separate conversations into one agent that you talk to from Herdr and the iPhone, by typing or by voice, and that works on several tasks at once. It adapts the Super-agent architecture from DeepSeek Harness (a Router, isolated Workers, event triggers, asynchronous tools, a watchdog) and the attention model of [Plannotator Inbox](https://github.com/backnotprop/plannotator#plannotator-inbox) (threads grouped by what they need from you, replies that wake the session that asked).

The shortest description is a small durable workflow controller with agents attached. The controller owns truth: work identity, events, lifecycle, delivery, permissions and resource ownership. The Router owns interpretation. Workers own execution. The surfaces own attention. Prompts never enforce correctness; code does.

## 1. Problem

Today every bbh conversation is its own island. You start one per task, usually in a Herdr pane, and you are the scheduler.

- You remember which pane holds which task. `herdr-agent-pick` (Ctrl+b a, Alt+Space a) and the attention-sorted agent panel help, but they list sessions, not work.
- A question an agent asks sits in its pane until you happen to look. From the phone you can only see sessions that have an Emacs front end.
- Long commands block a turn (`job-wait`), and what you type meanwhile queues until the turn ends.

Human attention, not model ability, limits how many tasks run at once. In practice you can follow 3 to 5 live sessions.

## 2. Goals and non-goals

Goals
- One agent to talk to. You never pick a session to give an instruction.
- Work is tracked as threads (a task, a topic), with one short list of what needs you.
- An agent's question reaches you on whichever surface you are on, and your answer wakes the session that asked without that session waiting idle.
- Several tasks run in parallel without mixing their contexts.
- Everything keeps working across REPL restarts, rollovers and a laptop sleep. Work resumes when the laptop wakes.

Non-goals for the first version
- An always-on cloud host. The laptop is the only machine. Section 11 covers moving the Router later.
- Team use or other people's messages. Every input is yours.
- GitHub and CI integrations, work the agent starts on its own, and Emacs views. Section 11 holds them.
- Replacing Herdr or the phone page. The design adds views to them.
- Compaction. bbh keeps its rollover model.
- Several Workers on one thread. A thread has one active Worker at a time (section 4).

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
| Origin | `:herdr`, `:phone`, `:voice`, `:job` | Where an event arrived. Never used for routing. |
| Resource key | `job:1234`, `repo:engstrand/guix` | The object an event concerns. Used for pass-through, and where external sources plug in later. |
| Thread id | `T12` | The bbh work stream that owns the resource. |

**Thread.** A unit of work you care about, such as "fix the keybinding check" or "Alt+W closes windows everywhere". It has a short handle `T<n>` that every surface shows and that you can type or say anywhere. A thread links to resource keys and lives as long as the work matters, which is longer than any one Worker.

**Event.** An accepted fact with a global sequence number: your message, a job exit, an ask, a reply, a Worker or `wf` state change. Events enter only through the controller (section 6).

**Worker.** A durable execution record: conversation id, `wf` run, jj workspace path, process identity, model, current job, granted capabilities and an ownership lease. A thread has at most one active Worker. When work arrives after a Worker has finished, the thread gets a new Worker generation that inherits the thread's history. An Emacs buffer is a view of a Worker, not its identity, so a restarted Worker gets a new buffer on the same conversation.

**Router.** One long-lived bbh conversation that reads your messages and the events no thread owns. It replies to you, starts Workers and relays the rare decision that crosses threads. It has no shell, and what it can start is limited by capabilities, not by its prompt.

**Ask.** Something a Worker needs from you: a `question` with options, an `action` only you can do, or a `review` of an immutable result. Each option has a stable id. `:stopped true` means the Worker cannot continue, `:holds-up "T14"` means another thread waits.

**Reply.** Your disposition of one or more asks on a thread: picks and notes, or an explicit dismissal. Replies are batched into one message per Worker and carry a stable reply id, so a repeated Send is one logical reply.

**Lease.** Who may send instructions to a Worker right now: the Router, or you after `takeover!`. Typing in a pane never changes the lease by itself.

## 5. Data model

### Store: SQLite

Authoritative orchestration state lives in `~/.local/share/bbh/super/super.db`, SQLite in WAL mode with `synchronous=FULL`, `foreign_keys=ON` and a 5 s busy timeout. The controller is the only writer. Views open read-only connections, so the Inbox buffer and the phone page query indexed tables instead of folding history, and they read while the controller writes.

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
 :resources #{"repo:engstrand/guix"}
 :worker "W7"                        ; active generation, or nil
 :history ["W3"]                     ; earlier generations
 :execution :waiting                 ; see dimensions below
 :resolved-at nil}

;; worker
{:id "W7" :thread "T12" :generation 2
 :conversation "20261102-114641-934" :wf-run "20261102-114816-571-change"
 :workspace "~/.local/share/bbh/super/workspaces/T12-2"
 :buffer "T12 keybindings"           ; its Emacs buffer, may be nil
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
| Archived | resolved, unless a row above matches. An ask opened after archiving needs you (archiving dismissed the asks open then); other new events show it as "came back" without unresolving. |

The badge counts the first three. Progress notes update the thread's summary line without making it New; only milestones (a `wf` phase change, a push, an ask) do. The list order holds while you look, and new rows wait behind an "N new" line.

## 6. Architecture

```
 producers                 bbh super (controller)                        consumers
 ---------                 ----------------------                        ---------
 phone page   --\          command socket (loopback)                    Router conversation
 Emacs buffers --+-------> validate, dedupe by command id, authorize     Worker conversations
 voice (bbh say) /            |                                           (bbh mailboxes)
 job exits, wf journal      commit: event + fold + outbox   ---------->  dispatcher drains the
                              |                                           outbox, retries until acked
 watchdog (health check)   super.db (SQLite, WAL)                        views read it read-only
```

**Controller.** `bbh super`, a Guix Home Shepherd service like `bbh-web`. It listens on a loopback socket for commands, each with a stable `command-id`. A loopback socket does not identify its caller, so every command carries a token the controller issued: one for you (the surfaces), one for the Router, and one per Worker, handed to the Worker's environment at start and revoked when it ends. Capabilities are bound to the token, not to the sender's claim. A command is deduplicated, authenticated, authorized, then committed. It holds no model and no conversation. Restarting it is safe at any point, because every effect it owes is in the outbox.

**Dispatcher.** Part of the controller. It drains `outbox` into the existing bbh mailboxes as section 5 describes. The mailbox stays the transport.

**Commands.** The whole API is a dozen commands, callable from a REPL helper, the CLI (`bbh super <command>`) or the socket.

```clojure
(thread! "Improve bbh web layout")                       ; => "T12"
(link! "T12" "repo:engstrand/guix")
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

**Fast path.** Every input is yours in this version, so the fast path is deduplication by command id and routing by handle or resource key. `policy.edn` names each origin's capabilities and concurrency caps, which is where external origins attach later without a new mechanism.

**Routing.** Deterministic first. A handle in the text (`T12`, spoken "tee twelve") or a resource key already linked to a thread resolves without a model. Only unresolved candidates reach the Router, and `jev-classify-task` runs before a Router turn so noise costs a Jev call. The Router sees linked events in its batch summary but does not act on them.

**Router environment.** A pinned bbh conversation whose manifest exposes only the commands above plus read functions (`threads`, `thread`, `events`). Reads inline the ids of related objects so the Router can follow them hop by hop. Its mailbox is drained in batches ordered by `seq`. The Router has delegation authority, not execution authority: `start-worker!` names the task and its origin, and the controller sets the Worker's capabilities from the authenticated origin and `policy.edn`. So a Router with no shell can start a shell-capable Worker for your request, and an origin added later cannot be talked into more than its policy grants.

**Workers.** Ordinary bbh conversations running a pstack workflow, with these additions.

- `start-worker!` creates a jj workspace per Worker (`jj workspace add`), so two Workers on the same repository never share a working copy, a `@` revision or generated files. The main checkout stays yours. The Worker's `wf` evidence directory is its own already.
- Named resource locks in `policy.edn`, such as `guix-build`, `repo-push` and `guix-system`. A Worker that needs one waits in `queued` with the lock named in its summary. `guix-system` is never granted to a Worker; a system switch is always an `action` ask to you.
- A concurrency cap, three Workers at first. A fourth is `queued`, not working.
- A Worker's buffer opens in the background of the `bbh-super` daemon, so Workers do not clutter what you are looking at.

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
- After a sleep, reconciliation checks what survived before restarting anything. While the laptop sleeps nothing runs: no Router, no page, no Workers. Section 11 is the only way past that.
- Only an unexplained anomaly starts a short model session, which can read the records and raise an `action` ask on the Watchdog thread.

**Full duplex, in short.** Your input never waits on a long tool call, because long work is a job and messages have the three delivery kinds above.

## 7. Surfaces

Both surfaces show the same threads, sections and handles. Emacs is the desk, because that is where the Router, the Inbox and the Workers run, seen through terminal frames in Herdr. The phone is for triage and answers away from it.

### Emacs: the main desk

The super-agent lives in one Emacs daemon, `bbh-super`, which the `bbh-emacs` Home service runs. You only see it through terminal frames (`emacsclient -s bbh-super -t`), usually in a Herdr pane. The controller talks to the daemon with `emacsclient --eval`. If the daemon is down, starting a Worker fails with an error that says so. There is no fallback to Herdr panes.

- **Inbox buffer, `*bbh inbox*`.** `bbh inbox` (`prefix+t` in Herdr) opens a terminal frame on it. It shows a live list of threads grouped by section, with the badge in the header line. When the buffer is created it opens a local port and registers it with the controller. The controller connects to that port whenever an event changes a thread, so the list reloads without polling. The order holds while you look, and new activity waits behind an "N new" line. A preview window below the list shows the thread under the cursor: the open ask with numbered options, or the Worker's latest state when nothing is open.
- **Router buffer, `Router`.** The Router conversation (`bbh acp --id router` in agent-shell, sandboxed by `BBH_ROLE=router`). `bbh super start-router` opens it and the Inbox. What you send from its composer or type at its prompt goes through `bbh super say!` with origin `:emacs`, so it gets an `oN` id the Router can start Workers for, and a leading handle ("T12 ...") steers that thread instead.

Keys in the Inbox buffer:

| Key | Action |
|---|---|
| j, k | Move between threads. The preview follows the cursor. |
| 1 to 4 | Pick an option on the selected thread's ask. The pick is saved at once. |
| r | Write a note on the selected thread, in the minibuffer. |
| s | Send every pick and note on the selected thread, as one message per Worker. |
| A | Approve the review the thread is waiting on. |
| RET | Open the buffer of the thread's latest Worker, running or finished, and mark the thread seen. |
| m | Mark read, which closes the asks and tells the Worker not to wait. |
| a | Archive. Open asks become dismissed, which the Worker is told. |
| n | Show the new threads waiting behind the "N new" line. |
| R | Open the Router. |
| g | Reload in the current order, new threads included. Opening the Inbox does the same. |

Each Worker gets one buffer, named after its thread (`T12 keybindings`). The buffer runs `bbh acp --id super-W<n>` in the Worker's jj workspace with its token and `WF_RUN`. It opens in the background and never takes focus. A restarted Worker replaces its old buffer.

- **Toasts.** Only Stopped and Waiting asks raise a Herdr system toast. Progress never does.
- **Taking over.** `/takeover` in a Worker's buffer runs `takeover!`, which moves the Worker's lease to you. The Router stops relaying to it until `/handback`. Typing in a buffer without the lease still works, but it is a plain prompt and does not change who the Router thinks is steering.

### iPhone: triage and answers away from the desk

The `bbh web` page gains an Inbox view next to the session tabs. It stays loopback-only behind `tailscale serve` and the owner check, and it keeps the page's Financial Times idiom: paper background, a serif masthead, small uppercase sans labels, hairline rules, claret for what needs you and teal for actions, square corners. There are no notifications; you open the page when you want to know.

- **Inbox list.** Sections and badge as in section 5. A row shows handle, title, the open ask's first line and age. Nothing updates while the laptop sleeps; the page says when its data is older than a few minutes.
- **Thread view.** Events as a chat. Asks show as cards with large option buttons and the recommended option marked. A tap saves the pick at once, and Send at the bottom sends every pick and note on the thread. Archive and "Mark read" (close the asks and tell the Worker not to wait) sit in the bottom bar beside Send.
- **Talk to the Router.** A composer at the bottom of the Inbox list sends to the Router. Typing a handle first ("T12 ...") goes to that thread.
- **Review on the phone.** A review card shows the summary, the gates that passed and the diff stat. Approving is allowed and names the exact revision. Line comments are left for the desk.
- **Hardening.** Text from Workers and jobs is rendered as text, never as HTML or Markdown links. Mutations carry the command id and the owner's Tailscale identity, requests are size-limited, and the page refuses cross-origin POSTs as `bbh web` does today.

### Voice: talking to the agent

Dictation is a way of talking to the agent, not only a way of typing into a window. It works the same from every surface and arrives as an ordinary event with `:channel :voice` and the transcript, so the Router and Workers know the text was spoken and may contain recognition errors.

- **At the desk, hold to talk to the agent.** Holding Cmd+Shift+M runs `hex dictation start`. Releasing it runs `hex dictation stop --print` and hands the transcript to `bbh say`, which appends a voice event. Nothing is pasted, so it works whatever window has focus. The binding is checked through kanata's three application layers and live Hyprland with Chrome focused; releasing Shift first suppresses plain Cmd+M dictation until the chord ends.
- **Where it goes.** If the Inbox has a thread selected and you spoke within a minute of selecting it, the voice event goes to that thread. A spoken handle ("T twelve, use plan B") goes to that thread. Anything else goes to the Router. Thread handles and project names are added to Hex's vocabulary so they are recognized, and the controller also accepts spoken forms such as "tee twelve".
- **Answering asks by voice.** "Option two", "the recommended one" or "yes, for a week" become picks on the selected thread's ask, shown in the Inbox. A dictated reply is a draft until you say "send" or press s. Voice never approves an `action` ask on its own, such as `make switch`.
- **Plain Cmd+M stays as it is.** It pastes at the cursor, so you can still dictate a note in the Inbox's `r` prompt, the Router buffer or a Worker's buffer.
- **Spoken replies.** None. The Router answers in text.
- **On the iPhone.** The composer accepts iOS dictation from the keyboard's microphone key with no extra work.
- **Screen and voice together.** `hex annotate` records the screen with drawing and narration. Attaching such a recording to a thread ("this pane looks wrong") gives a Worker both the picture and what you said about it.

## 8. User journeys

### J1. Morning triage on the iPhone

1. **iPhone.** At breakfast you open the bbh page. The badge says 2, and T14 sits under Stopped on you: "which Herdr key for the thread picker?".
2. **iPhone.** You open the thread. The card offers "Alt+Space t (recommended)" and "Alt+Space a". You tap the first and press Send.
3. **System.** The controller commits the reply and an outbox record together, and the dispatcher writes one message to the Worker's mailbox. The thread shows Sent until the Worker acks, then the Worker, idle since it asked, starts a new turn with your answer. If the laptop had been asleep, the Send waits on the phone as a queued command until the page reaches it.
4. **iPhone.** Back in the Inbox list, T9 shows under Waiting with a review. You read the summary (gates passed, 4 files), tap Approve, and T9 moves to Archived.
5. **iPhone.** You type "T11 also handle the phone layout" in the Router composer. The handle routes it without a model, as a `steer!` to T11's Worker. The Router sees it in its batch summary and does nothing.

### J2. Start a task at the desk

1. **Emacs.** You type in the Router buffer "Make the bbh web page show cost per session".
2. **System.** The Router calls Jev, gets `feature`, creates T15 and calls `start-worker!` with the `change` workflow. An Emacs buffer `T15 web cost` opens on the guix workspace and bbh starts there with `wf start change ...`.
3. **Herdr.** The Router replies in one line: "Started T15 (change workflow). I'll ask if it needs you."
4. **Herdr.** T15 appears in the Inbox under Working. You keep typing other requests. Nothing waits on T15.

### J3. A Worker needs a decision while you are busy

1. **System.** T15's Worker finds two ways to get the cost and calls `ask!` with `:stopped true` and a recommendation, then ends its turn.
2. **Herdr.** T15 moves to the top of the Inbox under Stopped on you. Herdr shows a toast.
3. **Herdr.** At the desk you press Alt+Space t, which opens the Inbox with T15 at the top. The preview shows the question with options 1 and 2. You press 1, then s.
4. **iPhone.** You could equally answer from the phone. If both you and the phone answered, the second Send carries a stale `:asks-version` and shows "already answered: option 1" instead of sending.

### J4. Changing your mind in another channel

1. **Herdr.** On Monday you told the Router in its buffer "T16 migrate to plan A".
2. **iPhone.** On Tuesday, on the phone, you write "T16 actually plan B, A breaks the Asahi build".
3. **System.** The message goes to T16's Worker as a new turn. The Worker reads its `wf` evidence, sees it is in `implement`, and either adjusts or calls `ask!` if plan B changes the shape. If so, its `wf` run goes back to `shape`, which `wf` already supports.

### J5. Taking over and handing back

1. **Herdr.** T15 is drifting. In the Inbox you press RET on T15 and type `/takeover` in its buffer, which runs `takeover!`.
2. **Herdr.** You type directly to the Worker for a few turns. The lease is yours, so the Router queues anything for T15 instead of relaying it.
3. **Herdr.** You type `/handback` in the Worker's buffer. The Router gets the queued items and resumes normal routing.

### J6. The laptop sleeps

1. **System.** You close the lid during a run. Every process stops, and nothing happens until you open it: no page, no Workers.
2. **System.** On wake the controller opens the database, SQLite recovers the WAL, and the dispatcher resumes unacked outbox rows. The `bbh-super` daemon starts again, and the health check restarts each Worker whose session died in a new buffer on the same conversation.
3. **System.** The health check reconciles each Worker record with reality. A Worker whose REPL survived is left alone. One whose REPL died but whose `wf` journal is intact is restarted once with its journal. A running job is rediscovered by pid, not launched again. A Worker that fails three restarts goes to Needs attention.

### J7. Sign-off at the desk

1. **Herdr.** T15 reaches `done`. Its `review` ask shows under Waiting.
2. **Herdr.** In the Inbox you press RET on T15 and read the revision in its Worker's buffer. You add two line comments with r and press s.
3. **Herdr.** The Worker gets both comments in one turn, fixes them and asks for review again, with a new revision. The earlier approval is stale by construction. You approve the new revision, the Worker commits with jj, and the thread is archived.

### J8. Talking to the agent at the desk

1. **Voice.** You are reading a PR in Chrome. Without switching windows you hold Cmd+Shift+M and say "start a task, make Alt Space a open the inbox pane, and check kanata for Cmd Shift M".
2. **System.** On release Hex prints the transcript and `bbh say` sends a command to the controller, which stores a voice event. Nothing was pasted into Chrome.
3. **Herdr.** No handle was spoken and no thread was selected, so the Router gets it, starts T18 with the `change` workflow, and its one-line reply shows in the Router buffer.
4. **Voice.** Later T18 stops with a question. You select it in the Inbox, hold Cmd+Shift+M and say "the recommended one, send". The pick and the Send show in the Inbox, and the Worker wakes.

### J9. Talking to the agent away from the desk

1. **iPhone.** Walking home you remember T16. You open the bbh page on the iPhone, tap the composer and use the keyboard's microphone: "T16, also keep the old plan as a fallback branch."
2. **System.** The handle sends it straight to T16's Worker. The event is marked as dictated, so the Worker treats an odd word as a likely recognition error rather than an instruction.

## 9. pstack and poteto-mode

- The Router does poteto-mode steps 1 and 2 for you. It classifies, picks the workflow from the route table and starts the Worker with `wf start`. The Router itself is not a workflow run, the same as the manual route.
- One Worker is one `wf` run, which matches today's limit of one run per conversation.
- `wf` states drive the inbox. `blocked` is Stopped on you, a phase failure stays inside the Worker, `done` makes a review ask. A model's prose never decides that a task is done.
- Gates and the independent reviewer are what will make work the agent starts on its own safe to run unasked, when section 11 adds it.
- `large-project` could later hand each stage of its plan to its own Worker, with the stage's check as that Worker's `--check`. Not in the first version.

## 10. Security

Every input in this version is yours, so the risks are mistakes and what a Worker can do with a shell, not hostile authors. Permissions are still capabilities granted by the controller per origin and enforced in code, and starting work is separate from publishing its effects, so external origins can be added later (section 11) without changing the model.

| Task origin | Capabilities |
|---|---|
| Your instruction (Herdr, phone, voice) | Today's bbh workflow permissions in the Worker's own jj workspace. Committing to the main checkout and pushing go through a `review` ask. |
| System change (`make switch`, services) | Never a capability. Always an `action` ask naming the exact command. |

- The controller, not the Router, decides a Worker's capabilities, from the authenticated origin of the request and `policy.edn`. An origin added later gets what the policy grants it and nothing more.
- A jj workspace isolates working copies, not processes. A Worker is a bbh REPL with a shell under your user, so a capability it lacks is enforced by what the controller will accept from its token, by resource locks, and by the review gate before publishing. Filesystem and network restrictions need real process isolation, such as a bubblewrap or Guix container around the Worker's REPL, and belong with the external origins in section 11. Until then the token boundary holds only against a Worker that uses its own token. Running as your user, a Worker can read your owner token from `~/.local/share/bbh/super/token`, or send the loopback web server a request with a `tailscale-user-login` header it made up, and either way it acts as you. Closing that needs Workers under a separate uid, or in a container without the token file and the loopback port. A check in the controller cannot close it.
- Approval refers to an immutable artifact: a revision id and the hash of its `wf` evidence. A later change makes the approval stale, and `approve!` on a stale revision is rejected.
- `wf` gates and the independent reviewer establish that code works. They are not a security boundary and do not stand in for your authorization of a side effect.
- Voice input is yours, but a transcription error is still an instruction, so dictated text never completes an `action` ask.
- The phone page keeps loopback binding, `tailscale serve` (never `funnel`) and the owner-login check, plus the hardening in section 7.
- Credentials stay out of events and asks. The controller refuses commands whose payload matches the secret patterns `make lint` already uses.
- nREPL ports stay on loopback. A remote endpoint (section 11) needs tailnet ACLs first.

## 11. Later

Each of these keeps the model above and adds an origin, a surface or a host.

- **GitHub and CI triggers.** A `gh` poller and a CI poller append events with external ids, deduplicated and coalesced per resource key. Comments on a PR linked to a thread pass through to its Worker; a CI failure on main is a candidate for the Router. They are gated on four properties being in place first: durable delivery (phase 1), execution isolation (phase 3), capabilities in code and token identity (phases 1 and 4), and clear ownership (phase 3). External text is shown to you but never reaches a model with tools unless `policy.edn` says so, and an external origin starts with read, edit own workspace and run checks, publishing only after your approval of the revision.
- **Work the agent starts on its own.** A CI failure on main starts a `bug-fix` Worker with `--repro` set to the failing check, in a thread marked speculative that stays in Quiet. It surfaces only when the `wf` run reaches `done` with an independent review; otherwise the output is discarded. Speculative Workers need real process isolation first.
- **Emacs views.** A thread view as an org buffer with asks as checkboxes beside a `jj diff`, an inbox buffer, and the bbh composer pinned to the Router, for long replies and line-by-line review.
- **An always-on Router.** Moving the controller and the Router to an always-on host on the tailnet makes the agent reachable while the laptop sleeps. Workers that need the laptop's working copy then wait for it, and on wake the laptop's bb nREPL serves as the endpoint that runs commands. Needs tailnet ACLs on nREPL first.

## 12. Cost and measurement

The DeepSeek ablation measured about 23M cache-read tokens per run for the two-level design against 6 to 11M for one session with subagents, on a synthetic load with simulated coding and five runs per design. Treat it as a direction, not a prediction for this laptop. Expect parallel Workers to use Claude subscription limits faster. Mitigations are deterministic routing, a Jev call before any Router turn, pass-through, and the concurrency cap.

Measure outcomes against today's manual Herdr workflow on a dozen real bbh tasks: time from request to Worker start, time spent waiting on you, time to done, routing mistakes, duplicate deliveries, recoveries, tokens, and the number of times you were interrupted. The goal is more finished work per unit of your attention, not more parallelism.

## 13. Rollout

The hard correctness properties come first, and the surfaces grow beside them. Each phase ends in a check you can run.

| Phase | Deliverable | Acceptance |
|---|---|---|
| 0 | Plannotator spike | Done 2026-11-02: a question went through its MCP endpoint and was read back; decision in section 14. |
| 1 | Controller and store | Done 2026-11-02: `bbh super` with `thread!`, `attach-worker!`, `ask!` and `reply!` over the store, the socket, tokens and the dispatcher. `bb test` covers the idempotent retry, the rollback, the replay of `events` into the materialized state and the mailbox ack; a live SIGKILL mid-reply kept the events and the retry returned its original result. `sqlite` is in the Home packages for the library. |
| 2 | Ask and reply protocol | Done 2026-11-02: supersede, `dismiss!` (mark read), `archive!`, the section rule as a pure function, `bbh super list`, `bbh inbox` (now the `*bbh inbox*` Emacs buffer, reloaded by the controller, `prefix+t` in Herdr) and the phone's Inbox and thread views over `/api/super`. Tests: the same reply id twice is one reply and one mailbox file; a stale Send is a 409 with the current asks; sections derive from a fixed event list. |
| 3 | Worker ownership and isolation | Done 2026-11-02: `start-worker!` records the Worker, then makes its jj workspace, its `wf` run and, since 2026-11-03, an Emacs buffer in the `bbh-super` daemon running `bbh acp --id` with the Worker's token and `WF_RUN` in its environment, and delivers the brief by mailbox; `finish-worker!` releases locks and promotes the next queued Worker; `takeover!` and `handback!` move the lease; `policy.edn` holds the cap and the locks. Tests on a temporary jj repository: two Workers get their own workspaces and the main checkout is untouched, a third past the cap is queued and starts when one finishes, a lock holder blocks a second, generations get their own workspaces. |
| 4 | Router and dispatch | Implemented 2026-11-02: `say!` routes handles and linked resources without a model and batches the rest to the Router by origin. The Router's session (`BBH_ROLE=router`) evaluates every tool call in `bbh.super.sandbox`, SCI with pure Clojure, `jev-classify-task` and the Router's controller commands and reads only, signed with its own token and never the owner's, and keeps no checkpoint, so it cannot run a shell, read files or replay code into the REPL. Its system prompt is the Router section alone. Tests and a live run against a temporary controller showed `super-thread!` and `super-threads` working and `sh`, `slurp` and Worker commands unresolved. Routing accuracy on real requests is not yet measured. |
| 5 | Jobs and recovery | Core implemented 2026-11-02: the controller rediscovers detached job files in active Worker conversations, commits each exit and its wakeup together, and does not relaunch jobs. A real detached-process test restarts the controller while the job waits, then verifies one execution, one event and one notification across another restart. Steering now uses typed durable mailbox messages: `steer!` and handle-routed input reach the next tool result, or the next hosted-model request, with a terminal fallback after a text-only turn. The Router cannot steer a human-leased Worker. Cancellation and recovery implemented: `cancel!` records intent, verifies detached job pid/start ticks and group leadership before killing, and waits for the session's out-of-band interrupt receipt before releasing locks. Unknown or lost job identities require human attention. Health observations include process identity, nREPL describe, workflow phase/status, jobs and oldest outstanding delivery/ask references, plus controller heartbeat. A dead managed Worker reuses its conversation, workspace and wf run with a rotated token, with three persisted restart attempts before failure; a surviving session is not replaced. A SIGKILL controller integration test rediscovers a running job without relaunching it. Core stage 5 is implemented and covered by offline integration tests; age-based escalation is implemented for the one aging signal that is not silence: a delivery a live Worker leaves in its mailbox past policy `:stale-delivery-minutes` (30) puts the thread under Needs attention with the reason as its row line, and clears when taken. Every needs-human classification carries such a line (an unidentifiable job, a missing wf run, a REPL that does not answer, a vanished session), and a failed Worker's row shows its failure reason, including a launch that throws (a missing `--check`, a bad repository, Herdr refusing a pane), which now fails the Worker and frees its slot instead of leaving it `starting`, so no anomaly the controller can name needs a model to explain it. Live Herdr replacement was accepted on 2026-11-02: a Worker stopped on an ask had its pane closed, the controller relaunched it in a new pane 51 s later with the same conversation and wf run, the resumed session kept waiting without refiling, and a reply finished it within 3 s. Actual suspend/resume and optional model-based anomaly interpretation are not acceptance-tested or implemented beyond the diagnostics described here. |
| 6 | Voice | Implemented 2026-11-02: `bbh say` accepts text/stdin or `start`/`stop` for Hex; stop uses `--print`, never paste. Explicit spoken handles override the inbox's selection, which expires after 60 seconds. Choices and recommended/label replies become versioned drafts; `send` submits them, while voice cannot pick or send action asks. `bbh say vocabulary` syncs handles and linked resource names. Cmd+Shift+M reaches Hyprland in all kanata app layers and was exercised with Chrome focused, including modifier-first release; Hex records and returns idle without pasting. Tests cover stale drafts, expiry, handle precedence, action refusal and silence. The recorded-clip acoustic acceptance for "tee twelve, option two" remains unverified. |

The first slice worth building is smaller than phase 1 and 2 together: the controller with one Worker per thread, `thread!`, `attach-worker!`, `ask!` and `reply!`, durable and testable without a model, plus `bbh super list` and the Inbox. That alone gives the key experience: a Worker asks, stops spending turns, and resumes from an answer sent elsewhere.

## 14. Decisions

- This version has two surfaces, terminal Emacs (in the `bbh-super` daemon, seen through Herdr) and the phone, and only your own input. GitHub and CI integrations and work the agent starts on its own wait for section 11.
- Voice uses Cmd+Shift+M for now. Hyprland has nothing on it; kanata and the application layers are checked when the bind is added, with the keybindings skill.
- Router replies are text only, also when the request was spoken.
- No push notifications. The phone shows what needs you when you open it, and the badge in the page title does the rest. The laptop cannot notify while asleep in any case.
- Plannotator: the phase 0 spike sent a question over its MCP HTTP endpoint and read it back, but replies reach an agent only by polling, the installed version lacks the documented `inbox call`, and a bridge would mirror its ask states into the controller. The ask lifecycle and both views are built in bbh. Plannotator can become a later surface through `send_message` and `wait_for_reply`.
- Router model: deterministic routing and Jev first, a mid-priced model for the rest. Routing accuracy is measured on real requests before the model is chosen.
- Memory: archived threads, their summaries, events and links to conversations and `wf` evidence are kept indefinitely and searchable. No memory subsystem until that is a problem.

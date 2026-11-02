# bbh super-agent: design

Status: proposal, nothing built. Written 2026-11-02.

This document describes how bbh could grow from separate conversations into one agent that you talk to from Herdr, Emacs and the iPhone, by typing or by voice, that works on several tasks at once, and that reacts to events such as GitHub comments. It adapts the Super-agent architecture from DeepSeek Harness (a Router, isolated Workers, event triggers, asynchronous tools, a watchdog) and the attention model of [Plannotator Inbox](https://github.com/backnotprop/plannotator#plannotator-inbox) (threads grouped by what they need from you, replies that wake the session that asked).

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

The design mostly connects these. The new parts are the thread store, the asks protocol, a small daemon, the Router's restricted environment, a voice channel, and three views.

## 4. Concepts

**Thread.** A unit of work you care about, such as "fix the keybinding check", PR `engstrand/guix#41`, or "plan the trip". Each thread has a short handle `T<n>` that every surface shows and that you can type anywhere ("T12 use plan B"). A thread can have several sources, such as a phone message that started it and the PR it produced.

**Event.** Anything that happens, such as your message (typed or dictated), a GitHub comment, a CI result, a job exit, a Worker's ask or a `wf` state change. Events get a global sequence number `seq` and are appended to one log.

**Worker.** A bbh conversation that owns one task. It runs in a Herdr pane, usually under a `wf` run, and subscribes to one or more threads. It never talks to you directly except through asks.

**Router.** One long-lived bbh conversation that reads your messages and the events no Worker owns. It replies to you, starts Workers and relays the rare decision that crosses tasks. It has no shell.

**Ask.** Something a Worker needs from you, in one of three kinds (after Plannotator).
- `question`, with options and a recommended one, optionally `:stopped true` (the Worker cannot continue) or `:holds-up "T14"` (another thread waits on it).
- `action`, something only you can do ("plug in the YubiKey", "approve the Guix switch"), with where to do it.
- `review`, a result to sign off, linking the diff and the `wf` evidence.

**Reply.** Your answer to one or more asks on a thread. Replies are batched. You pick options and write notes, and one Send delivers them as one message per Worker.

## 5. Data model

State lives under `~/.local/share/bbh/super/` as plain files, so any process can read it and a crash loses nothing.

```
super/
  events.jsonl        append-only, one event per line, global seq
  seq                 last seq handed out
  threads/T12.edn     one file per thread, rewritten by the daemon only
  policy.edn          trigger sources and fast-path rules
  router              the Router's conversation id
```

A thread file:

```clojure
{:id "T12"
 :title "Alt+W closes windows everywhere"
 :sources ["emacs:router" "gh:engstrand/guix#41"]
 :workers [{:conversation "20261102-114641-934" :pane "w1:p3" :wf-run "20261102-114816-571-change"}]
 :asks [{:id "a1" :seq 5120 :kind :question :text "Keep Ctrl+Alt+W as an alias?"
         :options ["No" "Yes, for a week"] :recommended 0 :stopped true}]
 :picks {"a1" 0}            ; saved immediately, sent on Send
 :draft ""                  ; unsent words
 :seen-seq 5100 :sent-seq 5050 :resolved-at nil}
```

Only the daemon writes thread files. Everyone else appends an event and lets the daemon fold it in, so there is one writer per file.

### Sections are derived, never stored

Each view groups threads by what they need from you. The first rule that matches wins.

| Section | Rule |
|---|---|
| Stopped on you | An open ask with `:stopped true`, or a `wf` run in `blocked`. |
| Holding up work | An open ask with `:holds-up`. |
| Waiting on you | Any other open ask, including a `review`. |
| New | Events after `:seen-seq` that are not asks, such as a Worker's progress note. |
| Working | A subscribed Worker is busy and nothing waits on you. |
| Quiet | Everything else that is not resolved. |
| Archived | `:resolved-at` set. A new event brings the thread back into the list, marked "came back", without unarchiving it. |

The badge counts only the first three sections. Unsent picks or a draft keep a thread out of Quiet. The list order does not change while you look at it. New activity waits behind an "N new" line, as in Plannotator.

## 6. Architecture

```
 sources                         bbh super (daemon)                      sessions
 ---------                       ------------------                      --------
 phone page  --\                  append event (seq)                    Router (bbh, no shell)
 Emacs       ---+-> events.jsonl -> fast path (policy.edn) --unowned--> reads batches, replies,
 gh poller   --/                  pass-through by thread  --owned--->   starts Workers
 herdr events, job exits, wf      fold into threads/*.edn               Workers (bbh in Herdr panes,
                                  write mailbox/ files     <----------   wf runs, ask!)
                                                                         views: Emacs, Herdr, phone
 watchdog (shepherd timer) reads everything, appends events
```

**`bbh super` daemon.** A Guix Home Shepherd service, like `bbh-web`. It tails sources, assigns `seq`, applies the fast path, folds events into thread files and writes mailbox messages for the Router or for subscribed Workers. It holds no model and no conversation state, so restarting it is safe. Written in babashka, with http-kit for any webhook endpoint and `core.async` for the source loops.

**Fast path.** `policy.edn` decides per source whether events are ignored, are candidates for the Router, or count only when they mention you.

```clojure
{:sources {:phone   :explicit
           :emacs   :explicit
           :gh      {:repos #{"engstrand/guix"} :authors #{"engstrand"} :default :candidate}
           :ci      :candidate
           :jobs    :owner-only}}
```

Events from unknown authors never reach a model with tools. The Router can still show them to you.

**Slow path.** Candidate events go to the Router. Before it spends a turn on them, `jev-classify-task` gives a typed route and probability, so plain noise costs a Jev call rather than a model turn.

**Pass-through.** An event whose source key is in a thread's `:sources` goes straight to the mailboxes of that thread's Workers. The Router still sees it in its batch summary but does not act on it, which the DeepSeek ablation found cuts Router tokens by about a third.

**Router.** A pinned bbh conversation with its own manifest. Its env exposes only data and dispatch functions, and the system prompt says it never edits files.

- `(threads)`, `(thread "T12")`, `(events :since seq)` read the store. Results inline the IDs of related objects, so the Router can follow them hop by hop (worker, `wf` journal, evidence) rather than load everything.
- `(start-worker! {:title :workflow :brief :threads})` classifies with Jev, opens a Herdr pane in the right workspace, starts bbh there with `wf start <workflow> --task ...`, records the Worker and subscribes it.
- `(relay! "T12" text)` writes to a Worker's mailbox.
- `(reply! channel text)` answers you on the channel you used.

Its mailbox is drained in batches. Everything that arrived during a turn becomes one prompt, ordered by `seq`.

**Workers.** Ordinary bbh conversations in Herdr panes, running a pstack workflow as now. Two helpers are added to `init.clj`.

- `(ask! {:kind :question :text ... :options [...] :stopped true})` appends an ask event and returns at once with `{:id "a1" :delivery :turn}`. The Worker either keeps working or ends its turn. Your reply arrives later through its mailbox as a new turn.
- `(note! text)` appends a progress event, which lands in New without notifying you.

`wf` state changes are events too, so `blocked` and `done` show up without the Worker saying anything.

**Full duplex.** Two small changes make a turn stop blocking you.

1. `job` writes a `job-exit` event when its command ends, so a Worker or the Router can start long work, end the turn and be woken with the result.
2. Mailbox items that arrive during a turn are appended to the next tool result, the same way bbh already appends status reminders. You do not have to wait for the turn to end to change course.

**Watchdog.** A Shepherd timer every 10 minutes runs a short, cheap bbh session over `bb stats`, `herdr agent list`, `wf` journals and the thread store. It looks for a Worker busy with no new `seq` for 30 minutes, a `wf` phase on its last visit, repeated REPL timeouts, a dead daemon, or asks older than a day. It fixes what it can (restart the daemon, re-deliver a mailbox item) and otherwise raises an `action` ask on a thread called Watchdog. You can open that thread and ask it why.

## 7. Surfaces

All three surfaces show the same threads, sections and handles. They differ in what they are good at. Herdr is the main desk, because that is where the Workers already run and where you spend the day.

### Herdr: the main desk

The first Herdr workspace is **Inbox**, pinned to position 1 so Ctrl+1 (Cmd+1) always reaches it. Its first tab has two panes.

- **Inbox pane (left, about 40%).** `bbh inbox`, a live list of threads grouped by section, with the badge count in the pane title. It is built like `herdr-agent-pick`, on fzf with `--listen`, so the daemon pushes a reload whenever an event changes a thread and the list stays current without polling. The order holds while you look, and new activity waits behind an "N new" line.
- **Router pane (right).** The Router conversation in bbh's terminal front end. Typing here talks to the Router, as the Emacs composer does.

Keys in the inbox pane:

| Key | Action |
|---|---|
| j, k | Move between threads. The preview shows the open ask with numbered options, or the Worker's screen when nothing is open. |
| 1 to 4 | Pick an option on the selected thread's ask. The pick is saved at once. |
| r | Write a note on the selected thread, in a small popup. |
| s | Send every pick and note on the selected thread, as one message per Worker. |
| Enter | Focus the Worker's pane in its project workspace. |
| e | Open the thread in Emacs, for a diff review or a long reply. |
| m | Mark read, which closes the asks and tells the Worker not to wait. |
| a | Archive. |

Workers run in panes in their project's workspace. The pane name starts with the thread handle (`T12 keybindings`). Herdr's agent panel still shows their states.

- **Alt+Space a focuses the inbox pane** from anywhere in Herdr, and Cmd+Space a does the same from any window by focusing the Herdr terminal first. It replaces the agent popup from 2026-11-02. Ctrl+b a keeps the plain agent picker for jumping straight to a pane.
- **Toasts.** Only Stopped and Waiting asks raise a Herdr system toast. Progress never does.
- **Taking over.** Typing in a Worker's pane is allowed. The Worker sees your message as a normal prompt, and the daemon records a `took-over` event so the Router knows you are steering that thread.

### Emacs: long-form review and writing

Emacs is where you go from the inbox when a thread needs more than a pick.

- **Thread view** (`e` in the inbox pane, or SPC o i). The thread's events in an org buffer, with asks as checkbox lists. `C-c C-c` on an option picks it, text under the ask adds a note, and `C-c C-s` sends. A `review` ask opens the diff with `jj diff` in a split, and comments on lines are added to the reply.
- **Inbox buffer.** The same sections as org headings, for when you are already in Emacs.
- **Router composer.** The existing bbh layout can be pinned to the Router conversation, so long instructions can be written with the Emacs editor.
- **Worker transcript.** One key opens a Worker's own transcript in agent-shell, for when you want to steer the details yourself.

### iPhone: triage and answers away from the desk

The `bbh web` page gains an Inbox view next to the session tabs. It stays loopback-only behind `tailscale serve` and the owner check.

- **Inbox list.** Sections and badge as in section 5. A row shows handle, title, the open ask's first line and age.
- **Thread view.** Events as a chat. Asks show as cards with large option buttons and the recommended option marked. A tap saves the pick at once, and Send at the bottom sends every pick and note on the thread. Archive and "Mark read" (close the asks and tell the Worker not to wait) sit in the header.
- **Talk to the Router.** A composer at the bottom of the Inbox list sends to the Router. Typing a handle first ("T12 ...") goes to that thread.
- **Notifications.** If you add the page to the home screen, iOS 16.4 and later can receive Web Push. Push is sent only for Stopped and Waiting asks, one per ask, and a newer push for the same thread replaces the older one. Lock-screen answer buttons are not assumed, because Safari's web push support for notification actions is unverified. A tap opens the ask.
- **Review on the phone.** A review card shows the summary, the gates that passed and the diff stat. Approving is allowed. Line comments are left for Emacs.

### Voice: talking to the agent

Dictation is a way of talking to the agent, not only a way of typing into a window. It works the same from every surface and arrives as an ordinary event with `:channel :voice` and the transcript, so the Router and Workers know the text was spoken and may contain recognition errors.

- **At the desk, hold to talk to the agent.** Holding Cmd+Shift+M runs `hex dictation start`. Releasing it runs `hex dictation stop --print` and hands the transcript to `bbh say`, which appends a voice event. Nothing is pasted, so it works whatever window has focus. Cmd+Shift+M is free in Hyprland today, but kanata and the application layers still need checking with the keybindings skill.
- **Where it goes.** If the inbox pane has a thread selected and you spoke within a minute of selecting it, the voice event goes to that thread. A spoken handle ("T twelve, use plan B") goes to that thread. Anything else goes to the Router. Thread handles and project names are added to Hex's vocabulary so they are recognized, and the daemon also accepts spoken forms such as "tee twelve".
- **Answering asks by voice.** "Option two", "the recommended one" or "yes, for a week" become picks on the selected thread's ask, shown in the inbox pane. A dictated reply is a draft until you say "send" or press s. Voice never approves an `action` ask on its own, such as `make switch`.
- **Plain Cmd+M stays as it is.** It pastes at the cursor, so you can still dictate a note in the inbox's `r` popup, the Router pane or a Worker's pane.
- **Spoken replies.** The Router answers in text. Reading short replies aloud is an open question (section 14).
- **On the iPhone.** The composer accepts iOS dictation from the keyboard's microphone key with no extra work. A Shortcut ("Tell bbh") that sends dictated text to the page over the tailnet would let you talk to the Router from the lock screen or Siri. Whether a Shortcut can pass the page's owner check is unverified.
- **Screen and voice together.** `hex annotate` records the screen with drawing and narration. Attaching such a recording to a thread ("this pane looks wrong") gives a Worker both the picture and what you said about it.

## 8. User journeys

### J1. Morning triage on the iPhone

1. At breakfast a push says "T14 stopped: which Herdr key for the thread picker?".
2. The tap opens the thread on the phone. The card offers "Alt+Space a (recommended)" and "Alt+Space t". You tap the first and press Send.
3. The daemon writes one message to the Worker's mailbox. The Worker, idle since it asked, starts a new turn with your answer and continues its `wf` run.
4. Back in the Inbox list, T9 shows under Waiting with a review. You read the summary (gates passed, 4 files), tap Approve, and T9 moves to Archived.
5. You type "T11 also handle the phone layout" in the Router composer. It goes straight to T11's Worker. The Router sees it in its next batch and does nothing, because T11 owns it.

### J2. Start a task at the desk

1. You press Ctrl+1 to reach the Inbox workspace and type in the Router pane "Make the bbh web page show cost per session".
2. The Router calls Jev, gets `feature`, creates T15 and calls `start-worker!` with the `change` workflow. A Herdr pane `T15 web cost` opens in the guix workspace and bbh starts there with `wf start change ...`.
3. The Router replies in one line: "Started T15 (change workflow). I'll ask if it needs you."
4. T15 appears in the inbox pane under Working. You keep typing other requests. Nothing waits on T15.

### J3. A Worker needs a decision while you are busy

1. T15's Worker finds two ways to get the cost and calls `ask!` with `:stopped true` and a recommendation, then ends its turn.
2. T15 moves to the top of the inbox pane under Stopped on you. Herdr shows a toast, and the phone gets a push.
3. At the desk you press Alt+Space a, which lands on T15 in the inbox pane. The preview shows the question with options 1 and 2. You press 1, then s.
4. You could equally answer from the phone or Emacs. If you answered twice, on the phone and in Emacs, the second Send finds the ask closed and says so.

### J4. A review comment on a PR you pushed

1. The `gh` poller sees a comment on `engstrand/guix#41` from an allowlisted author.
2. T12's `:sources` includes that PR, so the daemon passes the comment straight to T12's Worker mailbox.
3. The Worker, which finished earlier, gets a new turn, fixes the nit, pushes, and posts a short comment on the PR. It records a `note!`.
4. You see T12 under New in the evening. No push, because nothing waits on you.

### J5. Changing your mind in another channel

1. On Monday you told the Router in Emacs "T16 migrate to plan A".
2. On Tuesday, on the phone, you write "T16 actually plan B, A breaks the Asahi build".
3. The message goes to T16's Worker as a new turn. The Worker reads its `wf` evidence, sees it is in `implement`, and either adjusts or calls `ask!` if plan B changes the shape. If so, its `wf` run goes back to `shape`, which `wf` already supports.

### J6. Taking over and handing back

1. T15 is drifting. In the inbox pane you select T15 and press Enter, which lands in its pane.
2. You type directly to the Worker for a few turns. The daemon records `took-over`, and the Router stops relaying to T15 until you hand back.
3. You type `/handback` in the pane, or archive the thread. The Router resumes normal routing.

### J7. Silent fix in the background

1. CI fails on main overnight. `policy.edn` marks CI failures on main as candidates with the "silently reproduce" rule.
2. The Router starts a Worker on T17 with the `bug-fix` workflow and `--repro` set to the failing check, and marks the thread speculative.
3. Speculative threads stay in Quiet. If the `wf` run reaches `done`, with the reproduction failing first and passing after and an independent review, T17 moves to Waiting with a review ask. If the run blocks or gives up, the Worker's output is discarded, the thread is archived, and you were never interrupted.

### J8. The laptop sleeps

1. You close the lid during a run. Every process stops.
2. On wake the daemon replays `events.jsonl` from its last folded `seq`, and the mailbox redelivers anything not acked. Herdr restores panes and their bbh conversations, as it does today.
3. The next watchdog tick finds any Worker whose REPL died and asks it, through its mailbox, to resume from its `wf` journal.

### J9. Sign-off at the desk

1. T15 reaches `done`. Its `review` ask shows under Waiting.
2. In the inbox pane you select T15 and press e, which opens the thread view in Emacs. The diff opens beside the thread with the `wf` evidence links. You comment on two lines and press `C-c C-s`.
3. The Worker gets both comments in one turn, fixes them and asks for review again. You approve, the Worker commits with jj, and the thread is archived.

### J10. Talking to the agent at the desk

1. You are reading a PR in Chrome. Without switching windows you hold Cmd+Shift+M and say "start a task, make Alt Space a open the inbox pane, and check kanata for Cmd Shift M".
2. On release Hex prints the transcript and `bbh say` appends a voice event. Nothing was pasted into Chrome.
3. No thread was selected, so the Router gets it, starts T18 with the `change` workflow, and its one-line reply shows in the Router pane.
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

Voice input is your own, but a transcription error is still an instruction, so dictated text never approves an `action` ask. Trigger sources make untrusted text reach an agent that has a shell on a laptop with private files in `local/`.

- The fast path drops events from authors outside `policy.edn` before any model sees them. GitHub text from others is shown to you, never acted on.
- Workers started from an external event get the same checks as any other run. `make switch` and other root-equivalent actions still need you, through an `action` ask.
- The phone page keeps loopback binding, `tailscale serve` (never `funnel`) and the owner-login check. Web Push uses VAPID keys stored outside the repo.
- Credentials stay out of events and asks. The daemon refuses to write events whose payload matches the patterns `make lint` already uses for secrets.
- nREPL ports stay on loopback. A remote endpoint (section 11) needs tailnet ACLs first.

## 11. Later: an always-on Router

Moving the daemon and the Router to an always-on host on the tailnet makes the agent reachable while the laptop sleeps. Workers that need the laptop's working copy then wait for it. On wake, the laptop's bb nREPL (already how bbh runs a REPL) serves as the endpoint that runs commands. This needs the security work in section 10 first and is out of scope here.

## 12. Cost

The DeepSeek ablation measured about 23M cache-read tokens per run for the two-level design against 6 to 11M for one session with subagents, on a synthetic load. Expect parallel Workers to use Claude subscription limits faster. Mitigations are a Jev call before any Router turn, pass-through, and a cap on concurrent Workers (start with 3, queued Workers show under Working as "waiting for a slot").

## 13. Rollout

Each step ends in a check you can run, and each is useful alone.

1. **Thread store and inbox pane.** `events.jsonl`, thread files, `bbh inbox` in a pinned Inbox workspace, and Alt+Space a focusing it. Workers are still started by hand and `(thread! "title")` registers one. Check: a test that folds a fixed event log into the expected sections, and the pane reloading live when an event is appended.
2. **Asks and replies.** `ask!`, picks and Send in the inbox pane, the Emacs thread view, delivery through the existing mailbox. Check: a Worker asks, a Send from the inbox pane wakes it with exactly one message.
3. **Phone Inbox.** The web view with option buttons and Send. Check: the same journey from the phone on the tailnet.
4. **Full duplex.** Job-exit events and mailbox items appended to tool results. Check: a 2 minute job wakes an idle Worker, and a message sent mid-turn reaches the next tool result.
5. **Router.** The restricted env, `start-worker!`, batching, pass-through. Check: J2 and J5 end to end.
6. **Voice.** `bbh say`, the Cmd+Shift+M hold-to-talk bind, routing to the selected thread or a spoken handle, Hex vocabulary for handles. Check: J10 with Chrome focused, and a recorded clip of "tee twelve, option two" becoming a pick on T12.
7. **Triggers.** `gh` poller and `policy.edn`. Check: J4 with an allowlisted and a non-allowlisted author.
8. **Watchdog and Web Push.** Check: a deliberately stuck Worker gets flagged within one tick, and a push arrives on the iPhone.
9. **Speculative runs.** Check: J7 on a seeded failing check, both the fixed and the discarded outcome.

## 14. Open questions

- Is Cmd+Shift+M free through kanata and every application layer, or does voice need another chord?
- Should short Router replies be spoken back, for example with a local text-to-speech model, when the request came by voice?
- Can a Siri Shortcut reach the page through `tailscale serve` and pass the owner check?
- Should the Router run on Claude or a cheaper OpenRouter model? DeepSeek found a lighter model suffices for routing.
- Does iOS Web Push from a home-screen page support action buttons? If it does, single-choice asks can be answered from the lock screen.
- Should Plannotator Inbox be used directly as the ask surface instead of building one? It already has questions, guided reviews, batching, tailnet access and a paired iPhone app. The cost is a second store beside the thread store and less control over the Herdr and Emacs views.
- How long do archived threads stay recallable before long-term memory becomes the problem DeepSeek leaves open?

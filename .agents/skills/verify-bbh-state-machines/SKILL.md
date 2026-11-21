---
name: verify-bbh-state-machines
description: Model-check the bbh super Worker, ask and reply lifecycles, its finish effects and deadlines, and the wf run phases with TLA+. Use when a change touches desktop/build/bbh/src/bbh/super/model.clj, the effects in src/bbh/super.clj, src/bbh/workflow.clj or src/bbh/workflows/change.edn, or when you need to know whether an ordering of commands, attests or abandons can break one of their rules.
---

# Verify the bbh state machines

`cd desktop/build/bbh && bb verify:tla` (or `make bbh-tla-check`) runs TLC on every config in `verify/tla/`, about 30 seconds. `bb verify:tla Super.asks.cfg` runs one. It uses `tlc2` from PATH, else `guix shell tla2tools`. A config `M.NAME.cfg` checks `M.tla`. When a rule breaks, the runner prints its shortest trace (each state lists only the variables that changed), drops the rule and reruns the rest, so each broken rule gets its own trace. Exit 1 means a rule broke.

## What is verified

| Rule | Plain sentence | Harm when broken |
| --- | --- | --- |
| `CapRespected` | At most `Cap` launched Workers hold a slot (`holds-slot?`). | More processes than the policy allows. |
| `OneWorkerPerThread` | A thread has at most one queued or active Worker. | Two Workers act on one thread. |
| `NoLaunchOnArchived` | No Worker is launched for a thread that stays archived; a start reopens it. | A process starts for work the person put away. |
| `OpenAskHasWorker` | An open ask has a live Worker. | The person answers into the void. |
| `StoppedAskWaits` | An open stopped ask has its Worker waiting (`model/waiting?`, read from the asks, never stored) or being cancelled. | The Worker runs on without the answer it stopped for. |
| `TypedAnswerCloses` | Once a health pass saw the person type to a Worker, no ask opened before that prompt is open. | Asks answered in the chat keep the Worker waiting forever. |
| `AnsweredAfterTaken` | A reply is answered only after its addressee's live session took it from the mailbox. | A Worker marks a reply answered that it never read. |
| `NoReplyWaitsOnFinished` | No reply stays sent to a finished Worker; it is closed. | The thread is pinned under Sent after its Worker is gone. |
| `CancelledAfterTold` | A Worker ends cancelled only after its session took the cancel or ended. | A session runs on after its Worker is recorded cancelled. |
| `CancelEnds`, `CancelledNotHeld` | A cancel ends, or the person is shown the job nobody can identify that holds it (`model/cancel-plan`); a cancel completes only once no such job holds it. | A Worker stays cancelling with nothing saying why (W262). |
| `TimerKillsNothing` | The step that marks a Worker overdue moves no Worker, thread, ask or reply, and marks only a Worker in that state. | A deadline ends work the person did not end. |
| `OverdueStartingLaunched` | Only a launched Worker is found starting too long. | A queued Worker is reported as a session that failed to open. |
| `ChangedOnce`, `RecordedWasPerformed` | However often the controller dies, a finish effect changes the world at most once, and one recorded done was performed. | A workspace is removed or a working copy moved twice, or never. |
| `InOrder`, `TriesWithinCap`, `StuckIsLeftAlone`, `RecordsGrow` | Effects run in `model/effect-order`; one that failed `effect-tries` times is left to the person; records only grow. | The controller retries a failing effect forever or skips ahead of it. |
| `VisitsWithinCap` | No phase is visited more than `max-visits` times. | A run loops past its retry budget. |
| `Settled` | A done or abandoned run stays so; a blocked run can only be abandoned. | An abandoned run comes back as blocked or done. |
| `FollowsWorkflow` | The phase moves only to the current phase's `:next` or `:on-fail`. | A run skips or repeats phases whose gates never ran on the current code. |
| `VisitsGrow` | A phase's visit count never goes back. | `verdict-for` matches a verdict given for an earlier visit. |

Bounds. `Super.workers.cfg` has 3 Workers, 3 threads, a cap of 2 and no asks. `Super.asks.cfg` has 2 Worker generations on 1 thread, 2 asks and 2 replies. `Super.chat.cfg` adds turns, typed prompts and health passes (`Chat = TRUE`) on 1 Worker, 2 asks and 1 reply. `Super.timers.cfg` adds the deadlines (`Timers = TRUE`) on 2 Workers, 2 threads and a cap of 1. `Super.cancel.cfg` adds jobs nobody can identify (`Jobs = TRUE`) on 2 Workers and 1 thread, and checks its liveness rule under `FairSpec`: reconcile! passes and a live session takes its cancel; the person may never abandon. `Effects.finish.cfg` has one finished Worker's four effects, three tries each. `Workflow.change.cfg` has two overlapping `wf attest` calls and `Workflow.serial.cfg` has one. A pass covers these bounds only.

Left out. The `:for` record on Router commands (`StartWorker` models the implicit replace of a session-ended Worker and the reopen of an archived thread), the resume text, steering, job exits, what `health` reports beyond the live session, its turn and the person's prompts, a session the person reopens outside bbh, the Router's routing, outbox rows other than a reply's and a cancel's, the notification delay (a timer that changes no machine), tokens, `asks-version` staleness (the model's person always replies to the current version), and the SQL store. Gate results are chosen freely, so the wf model covers every pass, fail and pending order but not what a gate checks.

## Coverage

Each action in a `.tla` names the code it stands for in a comment: `Super.tla` points to the `decide*` and `fold*` methods of `src/bbh/super/model.clj` (`SessionEnds` to `health` and the process it saw, which `session-ended?` reads), `Effects.tla` to `settle!` and `effect!` in `src/bbh/super.clj` and `obligations` in the model, and `Workflow.tla` points to `attest!`, `decide`, `enter`, `fold-event` and `abandon!` in `src/bbh/workflow.clj` and the phase graph of `src/bbh/workflows/change.edn`. One `Super` action is one command, because `bbh.super.store` commits a command's events in one transaction. An effect is two actions, perform then record, with a crash possible between. `attest!` is two actions, because it reads the journal, runs the gates and only then appends.

Sync change: `qnsnumvylurr` (a jj change id, which survives rebases). Run `jj diff --from qnsnumvylurr --stat -- desktop/build/bbh/src/bbh/super/model.clj desktop/build/bbh/src/bbh/super.clj desktop/build/bbh/src/bbh/workflow.clj desktop/build/bbh/src/bbh/workflows/change.edn` to see what changed since. Move the sync change forward only after you have read those changes and confirmed that the models still describe them. For drift across many changes, follow /home/engstrand/guix/desktop/pi/agent/skills/pstack/maintain-formal-verification-skill/SKILL.md

## When you change covered code

1. Mirror the change in the action that names it, then run the check. TLC does not read the Clojure, so a change you do not mirror is not checked.
2. A new rule goes in the `.tla` under `rules` and in the config's `INVARIANTS` or `PROPERTIES`. Before you trust it, break the action it guards and watch it fail.
3. Keep the bounds small. A config that passes must finish in seconds; `Super` with every feature at once runs for hours, and `Timers = TRUE` in `Super.workers.cfg` alone makes 14 million states. A new feature gets a constant that turns it on and a config of its own.

## Reading a failure

Replay the trace against the real code before you call it a bug: for `Super`, drive `bbh.super.model/decide` and `fold` from `bb -cp src` one command per step; for `Workflow`, run `wf` with `XDG_STATE_HOME` set to a scratch directory and a slow `--check`. If the replay does not break, the model is wrong: fix the action until it matches the code. If it does break, report it with the trace in the code's words, and do not weaken the rule or change product code unless the person asks.

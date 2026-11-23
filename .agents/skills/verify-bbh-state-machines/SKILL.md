---
name: verify-bbh-state-machines
description: Model-check the bbh super Worker, ask and reply lifecycles, its finish effects and deadlines, and the wf run phases with Quint. Use when a change touches desktop/build/bbh/src/bbh/super/model.clj, the effects in src/bbh/super.clj, src/bbh/workflow.clj or src/bbh/workflows/change.edn, or when you need to know whether an ordering of commands, attests or abandons can break one of their rules.
---

# Verify the bbh state machines

`cd desktop/build/bbh && bb verify:quint` (or `make test ONLY=bbh-state-machines`) typechecks the models in `verify/quint/`, runs their `run` tests and a seeded `quint run`, then checks every model in `verify/quint/checks.edn` with TLC over every state, about two minutes. `bb verify:quint asks` checks one model, `--quick` skips TLC and `--mc` runs only TLC. A model is an instance module, such as `asks` in `super.qnt`, that fixes the constants. When a rule breaks, the runner prints its shortest trace as EDN, drops the rule and reruns the rest, so each broken rule gets its own trace. `:states` in checks.edn is the model's distinct-state count, so a change to the state space fails until you update it. Exit 1 means a check failed. From a REPL, `(quint/model-check file main rules)` returns the same results as data; see desktop/build/quint/README.md.

## What is verified

| Rule | Plain sentence | Harm when broken |
| --- | --- | --- |
| `capRespected` | At most `Cap` launched Workers hold a slot (`holds-slot?`). | More processes than the policy allows. |
| `oneWorkerPerThread` | A thread has at most one queued or active Worker. | Two Workers act on one thread. |
| `noLaunchOnArchived` | No Worker is launched for a thread that stays archived; a start reopens it. | A process starts for work the person put away. |
| `openAskHasWorker` | An open ask has a live Worker. | The person answers into the void. |
| `stoppedAskWaits` | An open stopped ask has its Worker waiting (`model/waiting?`, read from the asks, never stored) or being cancelled. | The Worker runs on without the answer it stopped for. |
| `typedAnswerCloses` | Once a health pass saw the person type to a Worker, no ask opened before that prompt is open. | Asks answered in the chat keep the Worker waiting forever. |
| `answeredAfterTaken` | A reply is answered only after its addressee's live session took it from the mailbox. | A Worker marks a reply answered that it never read. |
| `noReplyWaitsOnFinished` | No reply stays sent to a finished Worker; it is closed. | The thread is pinned under Sent after its Worker is gone. |
| `cancelledAfterTold` | A Worker ends cancelled only after its session took the cancel or ended. | A session runs on after its Worker is recorded cancelled. |
| `cancelEnds`, `cancelledNotHeld` | A cancel ends, or the person is shown the job nobody can identify that holds it (`model/cancel-plan`); a cancel completes only once no such job holds it. | A Worker stays cancelling with nothing saying why (W262). |
| `timerKillsNothing` | The step that marks a Worker overdue moves no Worker, thread, ask or reply, and marks only a Worker in that state. | A deadline ends work the person did not end. |
| `overdueStartingLaunched` | Only a launched Worker is found starting too long. | A queued Worker is reported as a session that failed to open. |
| `changedOnce`, `recordedWasPerformed` | However often the controller dies, a finish effect changes the world at most once, and one recorded done was performed. | A workspace is removed or a working copy moved twice, or never. |
| `inOrder`, `triesWithinCap`, `stuckIsLeftAlone`, `recordsGrow` | Effects run in `model/effect-order`; one that failed `effect-tries` times is left to the person; records only grow. | The controller retries a failing effect forever or skips ahead of it. |
| `visitsWithinCap` | No phase is visited more than `max-visits` times. | A run loops past its retry budget. |
| `settled` | A done or abandoned run stays so; a blocked run can only be abandoned. | An abandoned run comes back as blocked or done. |
| `followsWorkflow` | The phase moves only to the current phase's `:next` or `:on-fail`. | A run skips or repeats phases whose gates never ran on the current code. |
| `visitsGrow` | A phase's visit count never goes back. | `verdict-for` matches a verdict given for an earlier visit. |

Bounds. `workers` has 3 Workers, 3 threads, a cap of 2 and no asks. `asks` has 2 Worker generations on 1 thread, 2 asks and 2 replies. `chat` adds turns, typed prompts and health passes (`Chat = true`) on 1 Worker, 2 asks and 1 reply. `timers` adds the deadlines (`Timers = true`) on 2 Workers, 2 threads and a cap of 1. `cancel` adds jobs nobody can identify (`Jobs = true`) on 2 Workers and 1 thread, and its liveness rule assumes `fairness`: reconcile! passes and a live session takes its cancel; the person may never abandon. `finish` in effects.qnt has one finished Worker's four effects, three tries each. `change` in workflow.qnt has two overlapping `wf attest` calls and `serial` has one. A pass covers these bounds only.

Left out. The `:for` record on Router commands (`StartWorker` models the implicit replace of a session-ended Worker and the reopen of an archived thread), the resume text, steering, job exits, what `health` reports beyond the live session, its turn and the person's prompts, a session the person reopens outside bbh, the Router's routing, outbox rows other than a reply's and a cancel's, the notification delay (a timer that changes no machine), tokens, `asks-version` staleness (the model's person always replies to the current version), and the SQL store. Gate results are chosen freely, so the wf model covers every pass, fail and pending order but not what a gate checks.

## Coverage

Each action in a `.qnt` names the code it stands for in a comment: `super.qnt` points to the `decide*` and `fold*` methods of `src/bbh/super/model.clj` (`sessionEnds` to `health` and the process it saw, which `session-ended?` reads), `effects.qnt` to `settle!` and `effect!` in `src/bbh/super.clj` and `obligations` in the model, and `workflow.qnt` points to `attest!`, `decide`, `enter`, `fold-event` and `abandon!` in `src/bbh/workflow.clj` and the phase graph of `src/bbh/workflows/change.edn`. One `super` action is one command, because `bbh.super.store` commits a command's events in one transaction. An effect is two actions, perform then record, with a crash possible between. `attest!` is two actions, because it reads the journal, runs the gates and only then appends.

Sync change: `qnsnumvylurr` (a jj change id, which survives rebases). Run `jj diff --from qnsnumvylurr --stat -- desktop/build/bbh/src/bbh/super/model.clj desktop/build/bbh/src/bbh/super.clj desktop/build/bbh/src/bbh/workflow.clj desktop/build/bbh/src/bbh/workflows/change.edn` to see what changed since. Move the sync change forward only after you have read those changes and confirmed that the models still describe them. For drift across many changes, follow /home/engstrand/guix/desktop/pi/agent/skills/pstack/maintain-formal-verification-skill/SKILL.md

## When you change covered code

1. Mirror the change in the action that names it, then run the check. The checker does not read the Clojure, so a change you do not mirror is not checked. A scripted `run` test (`init.then(a).then(b).expect(p)`) pins a path you fixed.
2. A new rule goes in the `.qnt` under `Rules` and in the model's `:invariants` or `:properties` in checks.edn. A rule about one step is a property `always(orKeep(P, vars))` with `next(x)` for the value after. Before you trust it, break the action it guards and watch it fail.
3. Keep the bounds small. A model that passes must finish in seconds; `super` with every feature at once runs for hours, and `Timers = true` in `workers` alone makes 14 million states. A new feature gets a constant that turns it on and an instance module of its own.

## Reading a failure

Replay the trace against the real code before you call it a bug: for `super`, drive `bbh.super.model/decide` and `fold` from `bb -cp src` one command per step; for `workflow`, run `wf` with `XDG_STATE_HOME` set to a scratch directory and a slow `--check`. If the replay does not break, the model is wrong: fix the action until it matches the code. If it does break, report it with the trace in the code's words, and do not weaken the rule or change product code unless the person asks.

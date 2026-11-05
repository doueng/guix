---
name: verify-bbh-state-machines
description: Model-check the bbh super Worker, ask and reply lifecycles and the wf run phases with TLA+. Use when a change touches desktop/build/bbh/src/bbh/super/model.clj, src/bbh/workflow.clj or src/bbh/workflows/change.edn, or when you need to know whether an ordering of commands, attests or abandons can break one of their rules.
---

# Verify the bbh state machines

`cd desktop/build/bbh && bb verify:tla` (or `make bbh-tla-check`) runs TLC on every config in `verify/tla/`, about 15 seconds. `bb verify:tla Super.asks.cfg` runs one. It uses `tlc2` from PATH, else `guix shell tla2tools`. A config `M.NAME.cfg` checks `M.tla`. When a rule breaks, the runner prints its shortest trace (each state lists only the variables that changed), drops the rule and reruns the rest, so each broken rule gets its own trace. Exit 1 means a rule broke.

## What is verified

| Rule | Plain sentence | Harm when broken |
| --- | --- | --- |
| `CapRespected` | At most `Cap` launched Workers hold a slot (`holds-slot?`). | More processes than the policy allows. |
| `OneWorkerPerThread` | A thread has at most one queued or active Worker. | Two Workers act on one thread. |
| `NoLaunchOnArchived` | No Worker is launched for an archived thread. | A process starts for work the person put away. |
| `OpenAskHasWorker` | An open ask has a live Worker. | The person answers into the void. |
| `WaitingMeansStopped` | A Worker is `:waiting` only while one of its stopped asks is open. | The thread says it waits for the person while it does not. |
| `StoppedAskWaits` | An open stopped ask keeps its Worker waiting. | The Worker runs on without the answer it stopped for. |
| `VisitsWithinCap` | No phase is visited more than `max-visits` times. | A run loops past its retry budget. |
| `Settled` | A done or abandoned run stays so; a blocked run can only be abandoned. | An abandoned run comes back as blocked or done. |
| `FollowsWorkflow` | The phase moves only to the current phase's `:next` or `:on-fail`. | A run skips or repeats phases whose gates never ran on the current code. |
| `VisitsGrow` | A phase's visit count never goes back. | `verdict-for` matches a verdict given for an earlier visit. |

Bounds. `Super.workers.cfg` has 3 Workers, 3 threads, a cap of 2 and no asks. `Super.asks.cfg` has 2 Worker generations on 1 thread, 2 asks and 2 replies. `Workflow.change.cfg` has two overlapping `wf attest` calls and `Workflow.serial.cfg` has one. A pass covers these bounds only.

Left out. Restarts, steering, job exits, health, the Router's routing, outbox rows other than a reply's, tokens, `asks-version` staleness (the model's person always replies to the current version), and the SQL store. Gate results are chosen freely, so the wf model covers every pass, fail and pending order but not what a gate checks.

## Coverage

Each action in a `.tla` names the code it stands for in a comment: `Super.tla` points to the `decide*` and `fold*` methods of `src/bbh/super/model.clj`, and `Workflow.tla` points to `attest!`, `decide`, `enter`, `fold-event` and `abandon!` in `src/bbh/workflow.clj` and the phase graph of `src/bbh/workflows/change.edn`. One `Super` action is one command, because `bbh.super.store` commits a command's events in one transaction. `attest!` is two actions, because it reads the journal, runs the gates and only then appends.

Sync change: `syxnluvrqsnn` (a jj change id, which survives rebases). Run `jj diff --from syxnluvrqsnn --stat -- desktop/build/bbh/src/bbh/super/model.clj desktop/build/bbh/src/bbh/workflow.clj desktop/build/bbh/src/bbh/workflows/change.edn` to see what changed since. Move the sync change forward only after you have read those changes and confirmed that the models still describe them.

## When you change covered code

1. Mirror the change in the action that names it, then run the check. TLC does not read the Clojure, so a change you do not mirror is not checked.
2. A new rule goes in the `.tla` under `rules` and in the config's `INVARIANTS` or `PROPERTIES`. Before you trust it, break the action it guards and watch it fail.
3. Keep the bounds small. A config that passes must finish in seconds; `Super` with every feature at once runs for hours.

## Reading a failure

Replay the trace against the real code before you call it a bug: for `Super`, drive `bbh.super.model/decide` and `fold` from `bb -cp src` one command per step; for `Workflow`, run `wf` with `XDG_STATE_HOME` set to a scratch directory and a slow `--check`. If the replay does not break, the model is wrong: fix the action until it matches the code. If it does break, report it with the trace in the code's words, and do not weaken the rule or change product code unless the person asks.

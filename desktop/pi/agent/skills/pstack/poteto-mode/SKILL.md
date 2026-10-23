---
name: poteto-mode
description: Personal engineering workflow. Routes a task to a bb workflow (investigation, bug-fix, change) whose gates the runner checks. Use for /poteto-mode or non-trivial engineering tasks.
disable-model-invocation: true
---

# Poteto mode

Workflows are defined in `desktop/build/bbh/src/bbh/workflows/`. Run them with `wf`, from bash in Pi or with `(sh "wf ...")` in bbh. The runner executes every gate itself. A claim never advances a phase.

## Steps

1. For a new task, classify it with `jev_classify_task`. In bbh, call `(jev-classify-task task)`. Skip it for continuations such as "continue".
2. Pick the workflow from the route table. A route with no workflow is manual. Say so, then do the work directly.
3. Start the run: `wf start <workflow> --task "<task>" --cwd <repo> [--repro "<cmd>"] [--check "<cmd>"]`.
4. Follow the brief that `wf` prints. Write the evidence files it names into the evidence directory. Then run `wf attest --claim "<what you did>"`.
5. `wf attest` prints each gate. `FAIL` means the phase retries or goes back. `wait` means a verdict is missing. Record one with `wf verdict --reviewer <name> --verdict pass|fail --note "<why>"`. Use a fresh session for the review, not the session that did the work.
6. The run is finished when `wf status` says `done`. A `blocked` run needs the user. Report the block reason.

## Routes

| Route | Workflow |
|---|---|
| investigation, review | investigation |
| bug_fix | bug-fix |
| feature, refactor | change |
| performance, prototype, large_project, pr_workflow, session_handoff, skill_work, other | manual |

## Reply

- Say what changed and how it was verified. Say what you could not verify.
- Label each claim measured, inferred, or guess.
- Write without em dashes or mid-sentence colons.
- No pull requests. Commit with jj when the repo uses it.

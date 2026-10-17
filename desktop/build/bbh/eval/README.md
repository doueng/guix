# Compare Pi and bbh

This evaluator compares three configurations on the same generated projects and model.

- `pi` uses Pi's built-in read, bash, edit, and write tools.
- `pi-sci` uses the production SCI extension and `desktop/pi/agent/AGENTS.md` guidance.
- `bbh` uses its persistent REPL and production init file, with skill discovery and poteto-mode disabled for the comparison.

For a prompt treatment, the optional third argument selects profiles. `BBH_EVAL_BASELINE_INIT=/path/to/old-init.clj bb eval:compare /tmp/bbh-treatment 3 bbh-baseline,bbh,pi-sci` compares an old init snapshot with the current prompt and SCI. [The signatures experiment](SIGNATURES.md) records one such run.

All profiles exclude personal extensions and project instructions. Pi uses an isolated agent directory with links to the existing credentials and model configuration. bbh gets a private copy of its init file. These processes are not security sandboxes. They retain the user's operating-system permissions.

## Run the checks

Run these commands from `desktop/build/bbh`.

```sh
bb eval:graders
bb eval:contracts /tmp/bbh-contracts-new 3
bb eval:compare /tmp/bbh-live-new 3
```

The contract checks use a local scripted provider. They do not make model API calls. The live comparison spends OpenRouter credits using `deepseek/deepseek-v4.1-flash`. Three repetitions produce 36 live trials. Each prompt has a deadline, and each trial gets a fresh workspace. The order of profiles rotates across tasks and repetitions.

Use a new output directory for each run. The runners refuse to overwrite completed results.

## Live tasks

The four tasks are adapted from `desktop/build/pi-sci/eval/tasks.mjs`.

| Task | Acceptance criterion |
| --- | --- |
| `navigate-service` | Identify the actual OpenSSH configuration and port, despite obsolete README information. |
| `fix-module` | Repair numeric version comparison. Node's tests pass and the original test file remains unchanged. |
| `rename-function` | Rename the exported function, callers, tests, and documentation. Node's tests pass. |
| `failure-recovery` | Recover from an incorrect file path and select the service value rather than a default. |

`eval/bbh/tasks.clj` owns fixtures and graders. The grader check proves that untouched fixtures fail and reference solutions pass. The rename grader permits only the requested rename in the test file. It does not accept deletion of assertions.

## Contract checks

The local Pi provider in `fixture-provider.ts` emits scripted messages through the installed CLI. bbh uses its existing script engine. Pi receives streamed deltas; bbh receives a complete scripted string. The text check therefore tests end-to-end preservation, not equal stream chunking.

The checks cover whitespace and Unicode, visible tool failure followed by recovery, invalid model selection followed by another prompt, and overlap rejection followed by cancellation and recovery. The cancellation check waits for a file created by the running tool before interrupting it, then checks that the tool stops updating a heartbeat file.

These checks use Pi RPC and bbh ACP. They do not test UI focus or keystrokes. Queueing is outside this contract comparison because Pi owns queues in its runtime while bbh's interactive queue is in agent-shell.

## Artifacts

Each live trial records its workspace, raw JSONL events, stderr, a grade, and telemetry. `summary.json` aggregates the trial records. `manifest.json` records the profiles and model. New runs also capture source fingerprints and check them again after the run. The first baseline predates that fingerprint check.

Reported tool calls count top-level tool execution starts. They are not model requests. Reported tool errors are top-level failed executions, including expected failures while investigating the deliberately incorrect path. Pi/SCI can also have nested tool errors that this count does not include.

Provider errors and protocol failures prevent a passing trial even when a file happens to look correct. Reported provider costs are retained as diagnostics, but the evaluator does not normalize their pricing semantics or compare latency.

See [the baseline report](RESULTS.md) for results and limitations.

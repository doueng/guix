# Helper signatures experiment

## Verdict

Keep the expanded API reference in `desktop/configs/bbh/init.clj`. In the final paired run, bbh completed every task with fewer outer evaluations and fewer failed executions than the unchanged bbh prompt. This supports improving the model-facing interface before changing the execution engine.

The experiment does not establish a general advantage over Pi/SCI. It tests one model on four small tasks.

## Change

The model-facing prompt now describes the exact argument order and return values for `sh`, `p/shell`, `fs/list-dir`, `fs/glob`, `slurp`, `spit`, `read-lines`, and `edit!`. It includes JSON parsing and explains that intermediate values must be printed or returned together.

The helper implementations and tool loop are unchanged. `test/bbh/env_test.clj` executes the documented examples against the actual REPL, including nonzero process exits and both root-level and nested files.

## Method

The same four tasks and graders from the baseline were retained. Each task ran three times under each profile, with rotated order and fresh workspaces. Pi 1.3.0 and OpenRouter's `deepseek/deepseek-v4.1-flash` were used throughout.

The profiles were the old bbh init snapshot, the updated bbh init, and production Pi/SCI. Skill discovery and poteto-mode were disabled as in the original comparison. The baseline snapshot was saved before modifying production configuration.

## First iteration

| Profile | Passed trials | Outer evaluations | Failed outer executions |
| --- | --- | --- | --- |
| Old bbh prompt | 12 of 12 | 70 | 17 |
| Signature reference | 11 of 12 | 48 | 3 |
| Pi/SCI | 12 of 12 | 58 | 8 |

The signature reference removed the observed argument-guessing failures, but one rename trial still missed the README. The trace showed that the agent enumerated files with `fs/glob` and the pattern `**/*`, then used the same incomplete enumeration for its final check.

Babashka's `**/*` pattern omits root-level files. The model therefore never saw the README. The reference example had reinforced that mistake.

## Second iteration

The reference now uses `(fs/glob "." "**" {:hidden true})`, filtered with `fs/regular-file?`. It explicitly warns that `**/*` omits root-level files and that glob results include directories. A regression test verifies the difference with root-level and nested fixtures.

The entire interleaved cohort was rerun.

| Profile | Passed trials | Outer evaluations | Failed outer executions |
| --- | --- | --- | --- |
| Old bbh prompt | 12 of 12 | 70 | 16 |
| Final signature reference | 12 of 12 | 48 | 3 |
| Pi/SCI | 12 of 12 | 65 | 6 |

All three failed bbh evaluations in the final run occurred while following the deliberately incorrect path in the failure-recovery task. Some contained cascading errors after the initial missing file. No helper arity failures appeared in the final bbh cohort.

Both iterations recorded unchanged fingerprints for the files in their manifests. Self-review subsequently corrected the fingerprint glob to include root-level SCI source files, which those manifests had omitted. The difference between the SCI totals across the two cohorts also demonstrates model variability. No speed or cost conclusion follows from these call counts.

## Reproduce

From `desktop/build/bbh`, supply an old init snapshot and a new output directory.

```sh
BBH_EVAL_BASELINE_INIT=/home/engstrand/.cache/bbh-eval/signatures/baseline-init.clj \
  bb eval:compare /tmp/bbh-signatures-new 3 bbh-baseline,bbh,pi-sci
```

The optional third argument selects comma-separated profiles. `bbh-baseline` requires `BBH_EVAL_BASELINE_INIT`. The runner copies the snapshot into each baseline trial and applies the same eval-only skill suppression to both bbh arms.

## Evidence

- `~/.cache/bbh-eval/signatures/baseline-init.clj` is the old production configuration.
- `~/.cache/bbh-eval/signatures/run/` contains the first iteration.
- `~/.cache/bbh-eval/signatures/glob-run/` contains the final iteration.
- Each run keeps its manifest, raw event logs, graded workspaces, summary, and source-stability record.

The final automated checks passed 20 harness tests with 48 assertions, four Emacs integration tests, and the cold Doom startup test. The pre-existing Emacs system-sleep warning remains.

## Attention

This experiment was self-reviewed. The prompt is longer, and its cost impact was not normalized. The small sample does not cover durable helper creation, compaction, or long coding sessions. Runtime definitions in an existing conversation are not automatically replaced when its init file changes. New or restarted REPLs load the updated reference.

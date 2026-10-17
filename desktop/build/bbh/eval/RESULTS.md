# Pi and bbh baseline

## Verdict

Pi and Pi/SCI completed all tasks in this sample. bbh completed every task except one rename trial, where it missed the README. The sample is too small and narrow to establish a general coding-quality winner. It does show avoidable friction in bbh's helper interface.

## Method

The run used installed Pi 1.3.0 and the current bbh checkout on 2026-10-18. All three profiles used OpenRouter's `deepseek/deepseek-v4.1-flash`. Four tasks ran three times per profile with rotated order and fresh workspaces. A separate 12-trial pilot was used to check the runner and is excluded from this table.

The tasks come from the existing Pi/SCI eval suite. They are not upstream Pi Vitest tests executed against a different implementation. The contract scenarios are analogous to Pi's session regression tests, but use the installed subprocess interfaces.

## Live results

| Profile | Passed trials | Top-level tool calls | Failed top-level tool executions |
| --- | --- | --- | --- |
| Pi built-in tools | 12 of 12 | 76 | 5 |
| Pi with SCI | 12 of 12 | 52 | 5 |
| bbh | 11 of 12 | 72 | 16 |

Every profile passed all navigation, bug-fix, and missing-path-recovery trials. Pi and Pi/SCI passed all rename trials. bbh passed two of three rename trials.

In `rename-function__3__bbh`, the implementation, callers, and tests were renamed correctly. Node's tests passed. The README still named `parseConfig`, so the independent grader rejected the trial.

Many bbh failures came from guessed APIs rather than the task itself. Examples include `(sh "node" "--test")`, although the helper accepts one shell-command string, and `(fs/glob "**/*")`, although Babashka requires a root and pattern. One trial guessed `fs/list`, which does not exist. The model generally recovered, sometimes by reading its init file. The deliberately missing path also contributes expected tool failures.

The lower Pi/SCI call count is an observation for these configurations and tasks. It is not evidence of lower latency, lower cost, or a universal advantage. Nested calls are excluded from this metric.

## Scripted contract results

All three profiles passed all four contract scenarios in three repetitions. That is 12 checks per profile.

- Whitespace and Unicode reached the client unchanged.
- An explicitly failed tool was reported, then a later tool wrote the recovery artifact.
- Invalid model selection did not prevent a subsequent prompt.
- A second unqueued prompt was rejected during a running tool. Cancellation stopped that tool's ongoing heartbeat writes, and the next turn completed.

The first fixture used division by zero as its failure. SCI returned infinity, so that was not an equivalent failure trigger. The corrected fixture explicitly throws. The initial fixture failure is excluded from the contract baseline and remains in `/tmp/bbh-pi-contracts-first`. Self-review also replaced a weak delayed-file cancellation check with an ongoing heartbeat check. All final contract checks were rerun.

## UI observations

Both UIs were exercised sequentially in the same Herdr pane using local scripted responses and a 160-line reply. No live model was used for the UI checks.

| Interaction | Pi fullscreen | bbh in Doom agent-shell |
| --- | --- | --- |
| Long reply | Followed the latest output. | Followed the latest output. |
| Scroll while drafting | Preserved draft and displayed a jump-to-latest hint. | Preserved draft using M-v and C-v. No equivalent hint was observed. |
| Cancel while drafting | Escape interrupted the tool and retained the draft. | C-c C-k requested confirmation, then retained the draft. |
| Input | Compact terminal editor. | Separate Emacs composer with Ctrl-N submission. |
| Display overhead | More space available for transcript text. | Two mode lines and a composer header. An Emacs system-sleep warning also opened an extra window. |

Pi was easier to operate for this narrow terminal interaction. bbh gives an Emacs user a separate editor buffer and CIDER access, but those benefits were not assigned subjective scores. The keyboard replay is observational evidence, not a controlled human usability study. One attempted Emacs draft-clear sequence did not behave as intended under Doom/Evil, so no keystroke-count comparison is reported.

## Recommended next changes

The first recommendation was subsequently implemented and evaluated in [the signatures experiment](SIGNATURES.md). The results below remain the original baseline.

1. Put exact helper signatures and examples in bbh's model-facing prompt. Keep `fs/glob` distinct from Pi's `fs/glob` convenience function.
2. Add an explicit jump-to-latest action and hint to the bottom composer.
3. Resolve the Emacs warning window without suppressing unrelated warnings.
4. Add a repeated multi-turn helper-creation and restart task before drawing conclusions about persistent-REPL benefits.

No production bbh behavior was changed to improve this baseline.

## Evidence

The kept artifacts are under `~/.cache/bbh-eval/2026-10-18/`.

- `live/manifest.json`, `live/results.jsonl`, and `live/summary.json` describe the live cohort.
- `live/<task>__<repetition>__<profile>/events.jsonl` contains raw protocol output.
- `live/rename-function__3__bbh/ws/README.md` contains the missed rename.
- `contracts-hardened/results.json` records the final scripted checks. `contracts/results.json` preserves the earlier version.
- `ui/pi/` and `ui/bbh/` contain Herdr text snapshots.

Credential links and bbh's runtime data directory were excluded from the archive. Original absolute temporary paths remain in trial metadata and tool output. The archived workspaces and event files preserve the evidence.

## Attention

This report is self-reviewed. No independent reviewer was used.

The live evaluation does not cover compaction, durable helper creation, model switching with history, images, MCP servers, or queued-prompt UI behavior. Personal skill packs were disabled. The first baseline did not fingerprint all loaded source files. Pi's thinking level was explicitly off; bbh used its existing OpenRouter request defaults, so provider sampling settings were not proven identical. No statistical significance, speed, or cost claim is made.

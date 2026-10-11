# Evaluate the SCI harness against plain Pi

These scripts run the same goal-only tasks through real `pi` processes under several tool profiles, with one model held constant, and grade each trial by inspecting the workspace afterwards. Use them to check whether a codemode change helps a model finish tasks or makes it spend fewer tokens. The unit tests can't tell you that.

## Run an evaluation

From `desktop/build/pi-sci`:

```sh
node eval/check-tasks.mjs
node eval/run.mjs --model openrouter/deepseek/deepseek-v4.1-flash --attempts 5 --jobs 3 --run my-run
node eval/report.mjs ~/.cache/pi-sci-eval/runs/my-run
```

`check-tasks.mjs` proves that each grader rejects its untouched fixture and accepts a reference solution. Run it after you edit `tasks.mjs`.

`run.mjs` spends model credits. A run of 9 tasks, 4 profiles, and 5 attempts on DeepSeek V4.1 Flash cost about $0.55 and took 9 minutes with 4 jobs.

To compare a change, run the same model and attempts before and after it, then compare the two reports. `report.mjs` recomputes metrics from each trial's `events.jsonl`, so metric changes apply to old runs too.

## Options

| Flag | Default | Meaning |
| --- | --- | --- |
| `--model` | required | Pi model pattern, the same for every profile |
| `--thinking` | `medium` | Thinking level |
| `--profiles` | `vanilla,sci,hybrid` | Comma-separated profile IDs |
| `--tasks` | all | Comma-separated task IDs |
| `--attempts` | `1` | Trials per task and profile |
| `--jobs` | `3` | Concurrent `pi` processes |
| `--timeout` | `300` | Seconds before a trial is killed |
| `--run` | timestamp | Run directory name under `--out` |
| `--out` | `~/.cache/pi-sci-eval/runs` | Results root |

## Profiles

| Profile | Tools the model sees | Extra system prompt |
| --- | --- | --- |
| `vanilla` | `read`, `bash`, `edit`, `write` | none |
| `sci-bare` | `codemode` only (`codemode.mode: only`) | none |
| `sci` | `codemode` only | `desktop/pi/agent/AGENTS.md` |
| `hybrid` | `codemode` plus the plain tools (`codemode.mode: on`) | none |

Every profile runs with `--no-extensions --no-context-files --no-skills` and a fresh agent directory that links only `auth.json` and the model files. Personal extensions, skills, and the repository `AGENTS.md` don't leak into any profile.

## What a trial records

Each trial writes `trials/<task>__a<attempt>__<profile>/` with the workspace, `events.jsonl` from `pi --mode json`, `stderr.txt`, and `result.json`. The report shows these columns:

- `pass` comes from the task's grader, never from the agent's claim.
- `calls` counts the model's tool calls (round trips). `inner` counts tools that a codemode script ran.
- `call err` and `inner err` are the shares of those calls that returned an error.
- `prompt tok` is the context of the first request, which is the fixed cost of the profile's system prompt and tool declarations.
- `result chars` totals the tool result text returned to the model.
- `crashes` counts trials where `pi` exited nonzero without a timeout.
- The paired table compares each profile with `vanilla` on the same task and attempt.

## Limits

- Trials call providers directly. They don't exercise the Claude bridge, which cuts the codemode description at 2048 characters.
- Each task is a single prompt. No task covers `defsession` reuse across turns or a real Guix build.
- The tasks are small. A task that a few shell commands solve favours `vanilla`.

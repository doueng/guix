# Evaluate the SCI harness against plain Pi

These scripts run the same goal-only tasks through real `pi` processes under several tool profiles, with one model held constant, and grade each trial by inspecting the workspace afterwards. Use them to check whether a codemode change helps a model finish tasks or makes it spend less. The unit tests can't tell you that. [RESULTS.md](RESULTS.md) records what the runs so far found.

## Run an evaluation

From `desktop/build/pi-sci`:

```sh
node eval/check-tasks.mjs
node eval/run.mjs --model openrouter/deepseek/deepseek-v4.1-flash --suite base --attempts 3 --run my-run
node eval/report.mjs ~/.cache/pi-sci-eval/runs/my-run
```

`check-tasks.mjs` proves that each grader rejects its untouched fixture and accepts a reference solution. Pass a suite or task ID to check only those. Run it after you edit a task file.

`run.mjs` spends model credits. On DeepSeek V4.1 Flash, the base suite with 4 profiles and 3 attempts costs about $0.70. The two session tasks cost about $1.10 for 4 profiles and 3 attempts, and take 4 minutes per trial.

To compare a change, run the same model, suite, and attempts before and after it, then compare the two reports. `report.mjs` recomputes metrics from each trial's `events.jsonl`, so metric changes apply to old runs too. To report on some tasks only, pass the baseline profile and a comma-separated task list: `node eval/report.mjs <run> vanilla session-reuse,guix-build`.

After you fix a grader, run `node eval/regrade.mjs <run directory>...`. It grades each kept workspace again and rewrites `results.jsonl`, keeping the old file as `results.jsonl.bak`.

`node eval/scale-report.mjs <run>` prints, for each size in the scale suite, each profile's median cost and wall time relative to vanilla.

## Models and rate limits

To test the path you use every day, pass `--model claude-bridge/claude-opus-5-5`. Each trial then loads `pi-claude-bridge` and links `claude-bridge.json` and the bridge's Claude Code install into its agent directory. Bridge trials use your Claude subscription, which this Pi session shares, and report $0. A few dozen Opus trials reach the subscription's five-hour limit.

`openrouter/anthropic/claude-opus-5.5` bills at list price instead. A new OpenRouter account allows few Opus requests per minute, so run it with `--jobs 1`.

When a prompt ends on a rate-limit error after Pi's own retries, `run.mjs` waits and sends the same prompt again into the same session, up to `--retries` times. `result.json` records how many times each prompt ran in `invocations`. The report leaves out any trial in which a prompt still ended on a provider error, and counts it under `invalid (provider error)`.

## Options

| Flag | Default | Meaning |
| --- | --- | --- |
| `--model` | required | Pi model pattern, the same for every profile |
| `--thinking` | `medium` | Thinking level |
| `--profiles` | `vanilla,sci,hybrid` | Comma-separated profile IDs |
| `--suite` | all | Comma-separated suites: `base`, `scale`, `session`, `repo` |
| `--tasks` | all | Comma-separated task IDs |
| `--attempts` | `1` | Trials per task and profile |
| `--jobs` | `3` | Concurrent `pi` processes |
| `--timeout` | `300` | Seconds before a trial is killed |
| `--retries` | `3` | Resends of a prompt that ended on a rate limit |
| `--run` | timestamp | Run directory name under `--out` |
| `--out` | `~/.cache/pi-sci-eval/runs` | Results root |

## Profiles

| Profile | Tools the model sees | Extra system prompt |
| --- | --- | --- |
| `vanilla` | `read`, `bash`, `edit`, `write` | none |
| `sci-bare` | `codemode` only (`codemode.mode: only`) | none |
| `sci` | `codemode` only | `desktop/pi/agent/AGENTS.md` |
| `sci-nopy` | `codemode` only | `AGENTS.md` without the rule against generating Python or shell programs for data work |
| `hybrid` | `codemode` plus the plain tools (`codemode.mode: on`) | none |

Every profile runs with `--no-extensions --no-context-files --no-skills` and a fresh agent directory that links only `auth.json` and the model files. Personal extensions, skills, and the repository `AGENTS.md` don't leak into any profile. `sci-nopy` fails at startup if `AGENTS.md` no longer contains the exact rule it removes.

## What a trial records

Each trial writes `trials/<task>__a<attempt>__<profile>/` with the workspace, `events.jsonl` from `pi --mode json`, `stderr.txt`, and `result.json`. The report shows these columns:

- `pass` comes from the task's grader, never from the agent's claim.
- `calls` counts the model's tool calls (round trips). `inner` counts tools that a codemode script ran.
- `call err` and `inner err` are the shares of those calls that returned an error.
- `cached share` is the share of tokens read from the provider's prompt cache.
- `prompt tok` is the context of the first request, which is the fixed cost of the profile's system prompt and tool declarations.
- `result chars` totals the tool result text returned to the model.
- `list $` prices the recorded usage at the model's list prices from `~/.pi/agent/models-store.json`, including cache-read discounts. Subscription providers report $0, so compare runs by list cost.
- `crashes` counts trials where `pi` exited nonzero without a timeout.
- The paired table compares each profile with the baseline on the same task and attempt.
- For tasks with several prompts, the report also prints the median cumulative list cost after each prompt.

## Suites

| Suite | File | Tasks |
| --- | --- | --- |
| `base` | `tasks.mjs` | 13 tasks: small file, search, and edit work, plus `session-reuse` (2 prompts), `fanout-checks`, `fanout-services`, and `guix-build` |
| `scale` | `tasks-scale.mjs` | The same three tasks at three sizes: 4, 16, and 48 slow checks; 20, 200, and 1000 JSON files to join; and a 10k, 100k, and 500k line log to aggregate |
| `session` | `tasks-session.mjs` | `monthly-reports` (12 prompts that repeat one CSV report) and `ops-day` (13 dependent prompts over logs, configs, code, and tests) |
| `repo` | `tasks-repo.mjs` | 3 questions and 2 edits on a snapshot of this repository, modeled on prompts from real sessions |

A task with `prompts` continues one session with `--continue`. Each later prompt appends to the same `events.jsonl`.

The repo suite extracts commit `b7fd83a5` with `git archive`, and `guix repl` computes its answer keys. Its Makefile points `GUIX` and `sudo` at stubs, so a trial that runs `make update` cannot pull or reconfigure the host.

## Limits

- `guix-build` and the repo suite need a working Guix daemon.
- Codemode has no CSV parser, and no way to scan a large file inside the sandbox. Tasks that need either reward a profile that may call Python or a shell pipeline through `bash`.
- `run.mjs` keeps every workspace. The 500k-line log task writes 37 MB per trial.

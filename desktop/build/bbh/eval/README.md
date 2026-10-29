# Evaluate bbh

bbh has two kinds of evaluation. The trace tools read every bbh conversation, check it against cheap assertions, and collect good or bad labels from you and from an LLM judge. The comparison below runs fixed tasks through Pi, Pi/SCI, and bbh and grades the results. The design follows Hamel Husain's [Your AI Product Needs Evals](https://hamel.dev/blog/posts/evals/).

## Read, assert, and label traces

A trace is one conversation directory with a `messages.jsonl`. Real conversations live in `~/.local/share/bbh/conversations`. Comparison runs write theirs under each trial's `data/bbh/conversations`, and the trace tools join them with the trial's `result.json` and the run's `manifest.json`. With no arguments, the tools read the real conversations and `~/.cache/bbh-eval`. Pass directories to read others, such as a run in `/tmp`.

```sh
bb eval:lint [ROOT...]        # assertion hits and graded passes per cohort
bb eval:show DIR              # one trace as Markdown
bb eval:review [ROOT...]      # label unlabeled traces, newest first
bb eval:judge [ROOT...]       # LLM judge; with no roots, every trace you labeled
bb eval:agreement             # judge precision and recall against your labels
```

`eval:lint` groups traces into cohorts. A cohort is one comparison run and profile, or one day of real conversations. Each cell counts the traces in a cohort with at least one hit, so two cohorts show whether a change made a failure rarer. The report lists example traces for each assertion. A failure does not stop the command. The pass rate you accept is your decision.

`eval/bbh/traces.clj` owns the assertions. Each one was seen in real traces or is a known trap in babashka.

| Assertion | Hit when |
| --- | --- |
| `guessed-arity` | A step raised `ArityException`, usually a guessed helper signature. |
| `unresolved-symbol` | A step named a function or namespace the REPL does not have. |
| `pi-idiom` | The code used Pi codemode conventions such as `await`, `tools/...`, `fs/read`, or `fs/list`. |
| `root-blind-glob` | The code used a `**/` glob, which skips root-level files. |
| `repl-restart` | An evaluation timed out or died, and the REPL lost its definitions. |
| `error-streak` | Three or more failed evaluations ran in a row. |
| `no-final-answer` | A turn ended without assistant text after its last tool call. |

To add an assertion, add an entry to `assertions` after you see the failure in a trace. Add a test in `test/bbh/eval_test.clj` that builds a conversation with the failure.

`eval:show` and `eval:review` render the trace in the same way. Skill blocks shrink to one line. Each step shows its code, its result, the exception class when it failed, and the assertions it broke. Comparison trials also show their task and grader verdict.

Labels are binary, and each one carries a critique. They are appended to `~/.local/share/bbh/eval/labels.jsonl`, and the newest label per trace and labeler wins. Set `BBH_EVAL_DATA` to keep labels somewhere else, for example while you test the tools.

The judge runs Claude Code without tools on your subscription. It uses `opus` unless `BBH_JUDGE_MODEL` names another model. It gets `eval/judge.md` as its system prompt and the rendered trace without the grader verdict. Its labeler name includes a hash of `judge.md`, so an edited prompt starts a new labeler and keeps old verdicts comparable.

`eval:agreement` treats bad as the positive class. Precision is the share of the judge's bad verdicts that you also marked bad. Recall is the share of your bad labels that the judge caught. The true negative rate is the share of your good labels the judge kept good. Raw agreement misleads when most traces are good, so the command does not report it. The command prints every disagreement with both critiques. Use those critiques to edit `judge.md`, then rerun `eval:judge` and `eval:agreement`. Trust the judge on unlabeled traces only after its precision and recall hold on 25 to 50 of your labels, and keep labeling a sample after that.

## Runs and metrics

Every conversation also writes `runs.jsonl`, a local run tree in the style of LangSmith. A trace is one user turn. The `turn` run's id is the trace id, and that turn's `llm`, `tool` and `compaction` runs name it as `parent`. A `/compact` outside a turn is a trace of its own. Each run has `:start`, `:ms`, `:status` (`ok`, `error` or `interrupted`), `:model` and `:effort`. `llm` and `compaction` runs add `:usage`, `:tool-calls` and `:truncated`. `tool` runs add `:tool-call-id`, `:chars` and `:restart`, and a failed evaluation has status `error`. A `turn` run adds `:usage`, its summed tokens and cost, and `:messages`, the `[from to)` range it wrote in `messages.jsonl`. Runs reference content instead of copying it, so join on the message range or `:tool-call-id` to get prompts and outputs.

`bb runs:metrics [ROOT...]` prints one line per day and model with turn, llm and tool latency (p50/p95), tool failures, REPL restarts, tokens, cache share and cost. To use runs in an eval, read them with `bbh.runs/load-runs` and join on the conversation directory, which is also what labels key on.

Claude Code reports cost per turn only, so a Claude `llm` run has tokens and no `:cost`, while its `turn` run has the cost. A Claude request interrupted mid-stream can report its partial tokens, giving an `llm` run with status `interrupted`, while the turn's cost can be 0. A `tool` run's `:ms` leaves out the REPL restart that follows a timeout or interrupt.

## Compare Pi and bbh

This evaluator compares three configurations on the same generated projects and model.

- `pi` uses Pi's built-in read, bash, edit, and write tools.
- `pi-sci` uses the production SCI extension and `desktop/pi/agent/AGENTS.md` guidance.
- `bbh` uses its persistent REPL and production init file, with skill discovery and poteto-mode disabled for the comparison.

For a prompt treatment, the optional third argument selects profiles. `BBH_EVAL_BASELINE_INIT=/path/to/old-init.clj bb eval:compare /tmp/bbh-treatment 3 bbh-baseline,bbh,pi-sci` compares an old init snapshot with the current prompt and SCI. [The signatures experiment](SIGNATURES.md) records one such run.

All profiles exclude personal extensions and project instructions. Pi uses an isolated agent directory with links to the existing credentials and model configuration. bbh gets a private copy of its init file. These processes are not security sandboxes. They retain the user's operating-system permissions.

### Run the checks

Run these commands from `desktop/build/bbh`.

```sh
bb eval:graders
bb eval:contracts /tmp/bbh-contracts-new 3
bb eval:compare /tmp/bbh-live-new 3
```

The contract checks use a local scripted provider. They do not make model API calls. The live comparison spends OpenRouter credits using `deepseek/deepseek-v4.1-flash`. Three repetitions produce 36 live trials. Each prompt has a deadline, and each trial gets a fresh workspace. The order of profiles rotates across tasks and repetitions.

Use a new output directory for each run. The runners refuse to overwrite completed results.

### Live tasks

The four tasks are adapted from `desktop/build/pi-sci/eval/tasks.mjs`.

| Task | Acceptance criterion |
| --- | --- |
| `navigate-service` | Identify the actual OpenSSH configuration and port, despite obsolete README information. |
| `fix-module` | Repair numeric version comparison. Node's tests pass and the original test file remains unchanged. |
| `rename-function` | Rename the exported function, callers, tests, and documentation. Node's tests pass. |
| `failure-recovery` | Recover from an incorrect file path and select the service value rather than a default. |

`eval/bbh/tasks.clj` owns fixtures and graders. The grader check proves that untouched fixtures fail and reference solutions pass. The rename grader permits only the requested rename in the test file. It does not accept deletion of assertions.

### Contract checks

The local Pi provider in `fixture-provider.ts` emits scripted messages through the installed CLI. bbh uses its existing script engine. Pi receives streamed deltas; bbh receives a complete scripted string. The text check therefore tests end-to-end preservation, not equal stream chunking.

The checks cover whitespace and Unicode, visible tool failure followed by recovery, invalid model selection followed by another prompt, and overlap rejection followed by cancellation and recovery. The cancellation check waits for a file created by the running tool before interrupting it, then checks that the tool stops updating a heartbeat file.

These checks use Pi RPC and bbh ACP. They do not test UI focus or keystrokes. Queueing is outside this contract comparison because Pi owns queues in its runtime while bbh's interactive queue is in agent-shell.

### Artifacts

Each live trial records its workspace, raw JSONL events, stderr, a grade, and telemetry. `summary.json` aggregates the trial records. `manifest.json` records the profiles and model. New runs also capture source fingerprints and check them again after the run. The first baseline predates that fingerprint check.

Reported tool calls count top-level tool execution starts. They are not model requests. Reported tool errors are top-level failed executions, including expected failures while investigating the deliberately incorrect path. Pi/SCI can also have nested tool errors that this count does not include.

Provider errors and protocol failures prevent a passing trial even when a file happens to look correct. Reported provider costs are retained as diagnostics, but the evaluator does not normalize their pricing semantics or compare latency.

See [the baseline report](RESULTS.md) for results and limitations.

## SWE-rebench

The GitHub workflow `.github/workflows/swe-rebench.yml` runs bbh on SWE-rebench through [Harbor](https://github.com/harbor-framework/harbor), so a result can be compared with a published row on [swe-rebench.com](https://swe-rebench.com) without paying for reference runs. It only starts by hand, because every run spends OpenRouter credits.

The dataset `ibragim-badertdinov/swe-rebench-07-2026` holds the 111 problems in the leaderboard's default window, 15 May to 1 July 2026, across Go, Java, Python, Rust and TypeScript. Each task runs in a prebuilt image with 1 CPU, 4 GB and a 50-minute agent timeout. A leaderboard row averages several runs of SWE-rebench's own scaffold, while one bbh run has a binomial interval of roughly ±9 points. Compare a run only with a row for the same model and reasoning effort.

`harbor/bbh_agent.py` subclasses Harbor's generic ACP agent. It installs a static bb, bbh's sources and an eval init into the task container, and Harbor's ACP runner then drives `harbor/bbh-acp`. The eval init is the production init with skills and poteto-mode off, plus `BBH_EFFORT` as the manifest's `:effort`. bbh reports its cost through ACP usage updates, and its transcripts come back under each trial's `agent/bbh-data`, where `bb eval:lint` can read them.

### Run it

1. Add an OpenRouter key as the repository secret `OPENROUTER_API_KEY`. Use a key of its own with a credit limit, because a model in a task container can read its environment. The workflow scrubs the key from the results before it uploads them, and the repository is public.
2. In GitHub, open Actions, then SWE-rebench (bbh), then Run workflow. The defaults run all 111 tasks with `openrouter/openai/gpt-5.6-luna` at medium effort, matching the leaderboard's GPT-5.6 Luna [medium] row at 43.6% and $0.11 a problem. Set tasks to 2 and shards to 1 for a smoke run first.
3. The report job writes a summary to the run page. Each shard's Harbor job directory is an artifact for 30 days. To summarize downloaded artifacts locally, run `bb eval:swe-report --expected 111 DIR`.

Other rows with OpenRouter models are DeepSeek-V4 Pro [high] (`openrouter/deepseek/deepseek-v4-pro`, effort high, 40.2%, $0.15) and MiMo V2.5 Pro (`openrouter/xiaomi/mimo-v2.5-pro`, 46.5%, $0.10). `eval/bbh/swe_report.clj` keeps these rows for its comparison column.

`make swe-rebench-check` checks the integration offline. It loads the agent through Harbor's factory with the pinned Harbor, runs Harbor's ACP runner against `bbh-acp` with a scripted model, and lints the workflow. It does not build a task container, so the container install is only exercised by a real run.

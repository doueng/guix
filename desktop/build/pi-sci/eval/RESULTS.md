# What the harness eval found

This page explains what the runs in October 2026 measured about SCI codemode against plain Pi tools, and why the numbers come out the way they do. [README.md](README.md) explains how to run the eval. Raw runs live under `~/.cache/pi-sci-eval/runs/`, and `~/.cache/pi-sci-eval/decisions.tsv` logs each decision with its evidence.

## The short answer

SCI codemode doesn't make a model finish more tasks. With fixed graders, every profile passed every valid trial on Opus 5.5, and pass rates differed only on DeepSeek V4.1 Flash and GPT-6 Luna, where SCI was lower.

SCI usually costs more. Measured by list price, which counts cache reads at their discount, the median task cost 1.1 to 2.2 times vanilla's on the base and repo suites, and up to 9 times on the scale suite. Two settings came close to even:

- The repo suite, built from real prompts in this repository: sci cost 1.34× vanilla on DeepSeek and 1.26× on Opus.
- Long sessions on Opus: after the first prompt, each prompt cost SCI less than vanilla.

No measured setting showed SCI clearly cheaper. On Opus, long sessions could cross over after about 20 prompts, but that's a projection from single trials.

## Results by question

Each row compares a SCI profile with vanilla on the same task and attempt. `list ×` is the median per-pair ratio of list cost.

| Question | Model | Trials per profile | Result |
| --- | --- | --- | --- |
| Base tasks | DeepSeek | 39 | sci 2.17×, sci-bare 1.96×, sci-nopy 1.62× list cost; pass 97–100% everywhere |
| Base tasks | Opus (bridge, round 2) | 39 | sci-bare 1.14×, hybrid 1.24×, sci 1.39× |
| Base tasks | Opus (OpenRouter, round 4) | 13 | sci 1.48×, sci-nopy 1.56×; cheaper than vanilla in 0 of 13 pairs |
| Scale sweep | DeepSeek | 3 per size | 1.1× to 9.3×, with no trend toward parity as data grows |
| Long sessions | DeepSeek | 3 per task | each prompt after the first costs SCI 2.8–4.2× vanilla's |
| Long sessions | Opus | 1 per task | on `monthly-reports`, each prompt after the first costs SCI 0.71–0.78× vanilla's, and the first costs about 2.8× |
| Repo suite | DeepSeek | 15 | sci 1.34×, sci-bare 1.29×, sci-nopy 1.46×; cheaper than vanilla in 6 of 15 pairs |
| Repo suite | Opus | 5 | sci 1.26×, sci-bare 1.31×, sci-nopy 1.49× |

## Why SCI costs more

Four effects explain most of the gap. None of them is the interpreter.

**A larger fixed prompt.** Every request carries the codemode description. On DeepSeek the first request costs 1778 tokens for vanilla, 2663 for sci-bare, and 3058 for sci. Cache discounts soften this but don't remove it.

**More turns.** SCI took a median of 1.2 to 1.4 times vanilla's turns. On the DeepSeek base suite, 15–16% of codemode calls failed against 2% of vanilla's calls, and each failure costs a turn. Most failures are unresolved symbols that a model expects from Clojure on the JVM, such as `parse-long`, `format`, and `Math/ceil`, or an `await` outside an async function. The runtime now provides the first group and hints at the rest. Runs after that change are too few to measure it.

**Data printed into context.** SCI is meant to keep data in the sandbox and print only results. Agents often printed whole files instead and parsed them in the next script. On the 12-month CSV task, sci-nopy on DeepSeek printed each month's CSV, about 6000 tokens, before parsing it.

**No way to process a big file inside the sandbox.** `fs/read` returns at most 2000 lines and `search/text` at most 1000 matches. On the 500k-line log, SCI agents ran `grep` through `tools/bash` into a temporary file and paged that back in, while vanilla ran one `grep | sort | uniq -c` pipeline. That's why the log task was SCI's worst at 4–9× cost.

## Where SCI helps

- **Parallel slow work.** On 16 checks that each sleep 2 seconds, sci finished in 0.29× vanilla's wall time because its script ran them concurrently. At 48 checks vanilla backgrounded them in the shell and caught up.
- **Repeated prompts on a strong model.** On Opus, `monthly-reports` prompts 2 to 12 cost sci $0.0138 each against vanilla's $0.0194, with fewer turns and less output. The parser that the first prompt built paid off on every later prompt. DeepSeek rewrote the parser or printed the data each time and saw no such saving.

## The AGENTS.md parse rule

`AGENTS.md` tells the agent not to generate Python or shell programs for data work. The `sci-nopy` profile removes that one rule. The effect depends on the task:

- On the base suite, DeepSeek with the rule removed cost 0.79× sci and was cheaper in 28 of 39 pairs, at the same pass rate.
- On the base suite, Opus with the rule removed cost 0.97× sci, and 0.98× on the five parsing-heavy tasks. The rule makes no measurable difference there.
- On the repo suite, removing it cost more on both models: 1.46× vanilla against sci's 1.34× on DeepSeek, and 1.49× against 1.26× on Opus.

On DeepSeek the rule hurts CSV-style parsing, where SCI has no parser and Python does. On Opus it costs nothing there, and on repository work it helps both models. The single Opus trajectory that first suggested a fourfold cost from the rule did not repeat.

## Bugs the eval found

The eval found these harness bugs, each now fixed with a regression test:

- A structured read cancelled while its file opened killed Pi. This caused 9 of 180 trials in the first pilot.
- `(await f args)` and dotted names such as `tools.edit` ran as no-ops and reported success.
- Calls still running when a script ended were cancelled without a word.
- Every result repeated the absolute path, and agents treated partial `fs/read-jsonl` pages as complete.
- Progress updates resent the whole call list after every nested call. A 1000-file join wrote 1.69 GB of events.

It also found these bugs in the eval itself:

- The `ops-day` grader rounded 1.755% to 1.75 with floating point. The SCI agents' 1.76 was correct, and 6 SCI trials had been failed wrongly.
- Pi's automatic retries after a rate limit start a new agent run, which split prompts in the wrong place.
- Rate limits made trials look like task failures. Opus on the Claude subscription stopped after 3 trials, and OpenRouter's per-minute limit for new accounts broke 21 of 28 Opus trials at 4 jobs. Those runs are excluded.

## How far to trust these numbers

- Opus long-session and repo results come from one or five trials per profile. Treat them as directional.
- `dist/sci.js` was rebuilt at 14:57 BST on 11 October to add number parsing and the new hints. Every DeepSeek trial in the third round started before that time, and every valid Opus trial after it, so the two models ran different runtimes.
- On `ops-day`, the fixture forces every ninth log line into hour 14. On Opus, vanilla spent 12 turns investigating that pattern on prompt 4, which alone made vanilla look 0.15 dollars more expensive. Read `ops-day` cost comparisons with that in mind.
- DeepSeek's prompt cache through OpenRouter misses at random, which adds noise to its uncached-input numbers.

## Follow-ups

Ordered by expected payoff per hour.

1. **Rerun the DeepSeek matrix on the current runtime.** Every DeepSeek trial in round 3 predates number parsing, `format`, `Math`, and the new hints. Run base, session, and repo with 3 attempts, about $2, and compare the call-error rate and list cost with round 3.
2. **Add a host-side line scan.** For example, `fs/scan` takes a path, a regular expression, and an optional capture group, and returns matching lines or counts per capture, computed in Node. That closes the large-log gap without a shell. Measure it on the scale suite's log task.
3. **Add a CSV reader.** `fs/read-csv` would return rows as vectors, with quoted fields handled. Measure it on `monthly-reports` and `session-reuse` on DeepSeek, where SCI rewrote a parser every prompt.
4. **Shrink the fixed prompt.** The codemode description adds 900–1300 tokens to every request. Move rarely needed lines into the catalog, then measure `prompt tok` and list cost on the base suite.
5. **Run longer Opus sessions.** Extend `monthly-reports` to 24 prompts and run 3 attempts per profile on OpenRouter at one job, about $8, to test the projected crossover around prompt 20.
6. **Fix the `ops-day` fixture.** Make hour 14 the busiest without the every-ninth-line pattern, so no agent spends turns investigating it.
7. **Keep the parse rule for now.** It helps on repository work for both models and costs Opus nothing on data work. Revisit it on DeepSeek after the CSV reader ships.
8. **Make `fs/read-json` return the parsed value directly.** Its result and `all-settled` both use `:value`, and agents unwrapped the wrong one. This changes the API that existing scripts use.
9. **Teach `defsession` in the tool description.** Only the `sci` profile, through `AGENTS.md`, ever saved a helper. Measure helper reuse on `monthly-reports` with the hint in the description.
10. **Name `fs/write` and `tools.write` in hints.** Agents tried `fs/write` in 7 trials. Point it at `tools/write`.
11. **Fix the stale `verify.sh` line.** It runs `tests/pi-codemode.test.ts`, which an earlier commit deleted, so `make pi-sci-test` always fails at the end.
12. **Steady the process-signal test.** `deadline escalates past ignored SIGTERM` failed once at a load average of 7.5 and passed 3 of 3 runs alone.
13. **Run Terminal-Bench** once Docker and Harbor are installed, to test SCI on tasks outside this repository.
14. **Clear old runs.** `~/.cache/pi-sci-eval/runs` holds 6.7 GB, including a 1.69 GB event file from the crashed scale run and 38 MB per 500k-line log workspace.

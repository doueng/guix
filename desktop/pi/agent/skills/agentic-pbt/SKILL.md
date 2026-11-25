---
name: agentic-pbt
description: Improve the generators of a property-based test suite in rounds, Ephyra style, with the harness in desktop/build/pbt (test.check under babashka). Each round rewrites only the generator file, is judged by branch coverage and state-transition counts under a fixed seed set and trial budget, and is recorded in a ledger. Use when asked to explore more of a property suite, raise its coverage, hunt bugs with generators, add a suite to the harness, or replay Quint traces against the real code. Not for writing or changing properties.
---

# Agentic generator optimisation

People own correctness: the properties, the invariants and the observer that
says what a state is. You own exploration: the generator. A deterministic
harness, not you, judges each change. The background and numbers are in
/home/engstrand/guix/docs/agentic-pbt.html

Everything runs from `desktop/build/pbt`:

```bash
cd desktop/build/pbt
bb list                      # the suites in suites.edn
bb measure SUITE             # coverage, behaviour, failures, never-reached arms
bb try SUITE --note "why" --tokens N --minutes M   # judge your change, record it
bb ledger SUITE              # every round so far
bb mbt SUITE                 # replay quint run --mbt traces on the real code
```

## The rule that does not bend

Edit only the suite's `:generators` file (suites.edn). Never edit its
`:properties` files, suites.edn, the harness, or the code under test, and
never weaken a property to get a pass. `bb try` refuses a working copy that
changed any other file since `--base` (default `@-`), or whose property
files differ from the last accepted round, and `make lint` (`bb guard`)
refuses generator files that assert and property files that generate.

## The loop

1. **Read** the suite's entry in suites.edn, its property file (`check`,
   `observe`) and, for an `-mbt` suite, the Quint spec it names. Note what
   the property promises and which labels `observe` reports.
2. **Run** `bb measure SUITE`. If it fails, go to step 7 first.
3. **Read the numbers**: coverage (branch arms hit of the `:cover`
   definitions), states, transitions, 3-grams, histories, labels.
4. **Find what is unexplored**: the never-reached list (file, definition,
   line, arm), labels that are rare or zero, actions that never follow each
   other. Pick one gap.
5. **Change the generator** to reach it: new choices, weights, sizes,
   orderings, or a mode that builds the hard case on purpose (valid by
   construction, not filtered). One idea per round.
6. **Judge it**: `bb try SUITE --note "<the gap you aimed at>" --tokens N
   --minutes M`, with your token and time spend for the round if you know
   them. It measures the last accepted generator and yours on the same code,
   seeds and trial budget, and accepts only if coverage or transitions rose
   (or a new failure appeared), none of coverage, transitions and 3-grams
   fell, no label the old one reached is lost, and the faster of two runs
   took at most 1.25x + 20 ms. Exit 0 accepted, 3 rejected. A rejected change: revert the
   generator file (`jj restore <file>`) and try another gap.
7. **A failure** is shrunk and saved to `regressions/SUITE.edn`, replayed
   first on every run (`bb replay SUITE`). Triage it as STARTUP.md of Ephyra
   says: real bug (quote the rule it breaks), test bug, intended, or
   environment. Do not fix the code under test or the property yourself; report it.
8. **Stop** after the round budget you were given, or after two rejected
   rounds in a row with nothing new reached. Commit (`jj commit -m '[T..]
   pbt: SUITE round N: ...'`) and report the ledger table, failures and verdicts.

Coverage is a weak objective for concurrent code: prefer gaps in
transitions and labels (recoveries, orderings, late answers) over raw arms.
A rising number that tests nothing new is reward hacking; say so if you see it.

## Adding a suite (a person's job, or with their review)

Split the generators into `<component>/test/<ns>/gens/<name>.clj`; the test
file keeps the deftest and exposes `check` (input -> problems, [] when it
holds) and `observe` (input -> `{:steps [{:state :action}] :labels #{}}`).
Add the entry to suites.edn and run `bb baseline SUITE`. A model-based suite
also names `:mbt {:file :main :replay}`: a driver that performs each Quint
action on the real code and compares an abstraction of the real state with
the trace's.

## Under a bbh Worker, Pi or Claude Code

The commands are the same everywhere. A bbh Worker commits its rounds in its
workspace and asks for review of the ledger; Pi and Claude Code run them in a
checkout. `bb try` needs jj for the allowlist.

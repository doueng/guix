# quint

Quint from Babashka. The specs in this repo are Quint (`desktop/build/bbh/verify/quint`,
`desktop/build/rexmux/verify/quint`), and this library runs them for `bb verify:quint`,
for tests, and for Workers in a bbh REPL, where `quint` aliases `quint.core`.

## quint.core

Each call runs the quint CLI (Home's `quint`, else this repo's package through `guix build`)
and returns EDN.

| Call | Returns |
| --- | --- |
| `(quint/typecheck file)` | `{:ok? :errors [{:message :file :line :col}]}` |
| `(quint/test file {:main "m" :match "re"})` | `{:ok? :passed :failed :errors :traces {test trace}}` |
| `(quint/run file {:main :invariant :invariants :seed :max-samples :max-steps})` | `{:ok? :status :seed :trace}`, `:status` `:ok`, `:violation` or `:error` |
| `(quint/verify file {:main :invariant :max-steps})` | Apalache's bounded check: `{:ok? :trace :out}` |
| `(quint/model-check file main {:invariants [..] :properties [..]})` | TLC over every state, one rule per run: `[{:rule :kind :ok? :states :trace :out}]` |
| `(quint/repl file main)`, `(quint/repl-eval r "inc")`, `(quint/repl-stop r)` | a live `quint repl`: `{:ok? :out}` per input |

A trace is a vector of states, each `{var value}` with the module prefix dropped. Sets are
sets, maps maps, tuples and lists vectors, records keyword maps, and a sum type's value a
keyword (`:Idle`) or `[tag payload]` (`[:Busy 2]`). A liveness counterexample from
`model-check` is `{:states [...] :loop i}`, a lasso back to state `i`. TLC prints a map keyed by
strings as a record, so its keys come back as keywords.

`model-check` compiles the module to TLA+ (`quint compile`) and runs TLC from the Apalache jar
that the quint package carries, rather than `quint verify --backend tlc`. That gives one rule per
run, so each broken rule gets its own shortest trace. It also avoids the Apalache server that
every quint call shares on port 8822, since each compile here starts its own on a free port.
And it can check action properties: Quint prints `always(orKeep(P, vars))` as `[]([P]_vars)`,
which TLC refuses, and `unparen-box` rewrites it as `[][P]_vars`.

## quint.checks

`(quint.checks/-main dir & args)` reads `dir/checks.edn`, a vector of
`{:file :main :states :invariants :properties}`. It typechecks each file, then runs each
model's `quint test` and a seeded `quint run`, in parallel, and then `model-check`. A model whose
distinct-state count differs from `:states` fails, so a change to a model's state space shows.
`--quick` skips TLC, `--mc` runs only TLC, and naming models picks them.

## Writing a spec

- One parametric module per machine, one instance module per bound (`import m(N = 2).*`) that
  holds its `run` tests (`init.then(a).expect(p)`).
- A rule about one step is `temporal p = always(orKeep(P, vars))`, with `next(x)` for the value
  after the step. Write `next(s.field)`, not `next(s).field`, which Quint's effect checker
  refuses. Quint cannot say `ENABLED` in an invariant.
- Liveness is `temporal p = fairness implies ...`, where `fairness` is a conjunction of
  `weakFair(A, vars)` and `strongFair(A, vars)`.
- Quint has no `CHOOSE`: take the least of a set of ints (`fold`) instead.

## Language server

`lsp/` pins quint-language-server's npm dependencies; `bb lsp/update.bb` regenerates
`modules/engstrand/packages/quint-language-server.scm` from `lsp/package-lock.json`.
`bb lsp/test.bb` checks diagnostics, hover and go-to-definition over stdio. Doom's
`desktop/configs/doom/quint.el` gives `.qnt` files `quint-mode` and starts the server with
lsp-mode.

## Tests

`bb test` here runs `test/quint/core_test.clj` against `test/quint/fixtures/counter.qnt`, about
40 s.

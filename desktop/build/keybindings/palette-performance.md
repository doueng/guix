# Emacs palette performance

The compact protocol and query-result cache reduce the work before the Dusk popup opens. They keep the existing socket protocol and execution path. No catalogue-registration service was added.

## Results

The final paired run used 6,661 commands in two dedicated Doom daemons on this M1 machine. Five samples per version alternated before and after. The baseline is revision `0ba9cf35e24de6fd58d0c5cadec5fd02549d9453`.

Warm opening to a mapped popup fell from a median of **148.87 ms to 91.18 ms**. The ranges were 126.48 to 172.21 ms before and 75.80 to 108.68 ms after. All ten openings succeeded and were cancelled.

These measurements cover the request through Hyprland mapping the layer. They do not measure a completed GPU frame, display scanout, or keyboard-focus acquisition. The benchmark includes the `emacsclient` trigger and polling overhead. Direct M-x does not pay the client-process cost.

All times below are medians in milliseconds. Stage timings overlap with composite timings and must not be added together.

| Operation | Before | After | Interpretation |
| --- | ---: | ---: | --- |
| Cold Emacs catalogue | 237.59 | 226.33 | No demonstrated improvement. Wide GC-related variation |
| GC within the cold catalogue | 82.89 | 89.67 | A component of the preceding row |
| Warm buffer filtering and history order | 6.93 | 6.41 | No meaningful improvement claimed |
| Assemble the cached wire fragments | 3.00 | 1.84 | Less text to join |
| Rust EDN parse | 3.18 | 0.86 | Release build of the production parser |
| Rust forward-message encoding | 0.95 | 0.39 | Release build of the production encoder |
| Babashka EDN parse | 20.08 | 3.83 | Exact emitted catalogue payload |
| Open and normalize entries | 0.14 | 4.95 | After now prepares the empty-query results here |
| Empty-query layout preparation | 7.34 | 0.82 | Scoring and sorting removed |
| Query update plus layout preparation | 7.76 | 7.61 | Work moves from render into query update. No search-speed improvement claimed |
| Encode the visible layout | 3.87 | 3.91 | Unchanged |
| Move selection and prepare layout | 20.51 | 0.25 | Reuses the current matches |
| Small request round trip | 0.86 | 3.12 | No improvement claimed. Ranges overlap substantially |
| Full catalogue request round trip | 118.51 | 60.51 | Includes encoding, IPC, both parsers, queueing, and the tiny count reply |
| Warm opening to a mapped popup | 148.87 | 91.18 | Composite live measurement |

The payload shrank from 539,508 bytes to 178,519 bytes. Compact rows contain `[command keys?]`, and the source is sent once. The command name is no longer repeated as both a label and a value.

Matching still examines every candidate when the query changes. Empty queries retain the supplied history order without a sort. Redraws, repeated queries, and selection movement reuse the matches already computed for the current query.

## Measurements after each change

Each experiment compared its candidate with the same baseline in five alternating pairs.

| Candidate | Baseline median mapped ms | Candidate median mapped ms | Candidate range ms |
| --- | ---: | ---: | --- |
| Compact rows only | 168.28 | 101.07 | 81.63 to 123.74 |
| Compact rows and cached query results | 198.09 | 85.49 | 73.32 to 108.59 |
| Final run with IPC measurements | 148.87 | 91.18 | 75.80 to 108.68 |

The batches have different load and allocation histories. Do not subtract candidate medians across batches to claim the isolated opening-time benefit of search caching. The navigation benchmark and its recomputation-count test establish that benefit directly.

## Remaining cost

The full-catalogue IPC measurement is still substantial compared with the small control request. The current design still transfers and reconstructs the catalogue on each opening. A registry could remove that work, but would add invalidation and restart recovery. This change stops at the smaller protocol and result-cache fixes.

Cold catalogue construction is not fixed. The final daemons used the installed configuration's `gc-cons-threshold` of 800,000 bytes. Both versions incurred collection during cold builds. The benchmark records collection counts and elapsed GC time rather than raising the threshold for one version. First use in a new buffer remains slower than a warm opening.

The machine had eight cores and load around 1.2 before the run. Chrome and the compositor remained active. Alternating versions reduces drift but does not remove scheduling and GC noise.

## Reproduce

From the repository root, run:

```sh
bb desktop/build/keybindings/palette-bench.bb \
  0ba9cf35e24de6fd58d0c5cadec5fd02549d9453 \
  /tmp/palette-bench
```

The benchmark opens and cancels ten live popups. It checks command counts, actual search results, successful replies, and layer creation. It starts isolated Emacs daemons, disables their history saving, restores the current Dusk config, and stops those daemons afterward. Keep other palette sessions closed during the run.

The output directory contains request payloads, phase samples, summaries, machine load, daemon logs, and a success or failure count. `palette-bench-results.edn` retains the measured samples and summaries from this change without saving the command catalogue.

`palette-bench.el` times the Emacs phases. `desktop/build/dusk/palette-bench.bb` exercises the real runtime handlers and layout serializer without IPC. `desktop/build/dusk/edn-bench.rs` builds with `rustc -O` and times the production parser and encoder against the emitted messages.

Unit tests check key hints, escaping, returned command IDs, buffer filtering, history, duplicate labels, cancellation, and reuse of search results. A live selection also preserved the Emacs prefix argument `(4)`. `make keybindings-check` and `make dusk-test` pass. `make doom-check` remains blocked by the existing environment-file PATH warning.

# SCI codemode for Pi

[manual.html](manual.html) is the full technical manual: a tutorial, how-to guides, reference, and design explanation.

This extension lets Pi run Clojure through SCI inside its existing QuickJS WebAssembly sandbox. Pi still executes tools, applies guards, accounts for model usage, saves images, and persists branch-local storage.

## Build and verify

The build needs Clojure CLI and its Java runtime. Verification also needs Node.js 22.19 or newer, npm, Bun, Python 3, Jujutsu, GNU Guix, GNU Make, and the installed `pi` command. On non-FHS Linux, the Biome launcher uses binutils' `readelf` to select the existing runtime loader. This Guix configuration already includes Clojure tools and Pi.

Run these commands from the repository root:

```sh
make pi-sci
make pi-sci-test
make stow
```

The first build fetches pinned Clojure dependencies and installs locked npm dependencies without lifecycle scripts when missing. The framework adapter integrates with the installed Pi. The structured file/search adapters use checked Pi SDK 1.0.4 implementations and their locked helper dependencies.

`make stow` builds SCI before linking the extension. Restart Pi or use `/reload` after changes. Build artifacts stay in the ignored `dist/` directory. The compiler publishes the bundle with an atomic rename.

The checked runtimes are the installed Pi 1.0.0 binary and Pi SDK 1.0.4. Tests exercise the actual CLI, terminal UI, and Stow-style symlinks. The SDK tests use deterministic local providers. They do not send prompts, files, or credentials to a service.

### Run a focused SCI check

From `desktop/build/pi-sci`, run:

```sh
node scripts/run-sci.mjs '(text (+ 20 22))'
```

The runner uses the locked Pi SDK and its real QuickJS worker with a deterministic local provider. It prints only the codemode result and exits nonzero when evaluation fails. The fixture provider makes no network requests. The runner permits only read-only `fs` tool operations. It does not load personal extensions or their policy hooks. Use the live Pi session for policy-sensitive work. Its working directory is a temporary test directory, so use absolute paths for existing files. This avoids the large event stream from CLI JSON mode. The verification suite separately tests the installed Pi CLI.

## SCI-only configuration

The replacement extension lives at `desktop/pi/agent/extensions/sci-codemode/index.ts`. Disable the built-in JavaScript codemode extension in Pi settings:

```json
{
	"defaultTools": ["+codemode"],
	"extensions": ["-builtin:codemode"],
	"codemode": {
		"mode": "only"
	}
}
```

Codemode accepts only Clojure through SCI. There is no language selector or alternative evaluator. Obsolete `codemode.language` fields have no effect. SCI reports invalid Clojure source as an error. A missing interpreter bundle causes an error, not a switch to another language.

SCI compiles to JavaScript internally so it can run inside QuickJS. That implementation detail does not enable JavaScript input. No patched Pi binary is required. The extension preserves Pi's original parameter schema identity so built-in MCP support recognizes codemode.

## Clojure programs

The installed Pi `docs/codemode.md` describes the disabled JavaScript evaluator. This README and its local links describe the active SCI extension.

Code is raw Clojure source. The whole program is an async body. Pi's tools return promises, so `await` suspends evaluation until a result arrives. Pure Clojure computation remains synchronous. See [Async Clojure in Pi](async-clojure.md) for the reason and tradeoffs.

```clojure
(let [result (await (fs/read-json "package.json"))]
	(text (:name (:value result))))
```

Use explicit await for every effect. Put require declarations before executable forms. Only provided namespaces can be required. Aliases `tools`, `catalog`, `models`, `json`, `str`, and `set` are already installed.

Helpers containing await need async metadata:

```clojure
(defn ^:async read-package [path]
	(:value (await (fs/read-json path))))

(await (read-package "package.json"))
```

Independent calls can run concurrently:

```clojure
(let [results
      (await
       (all-settled
        [(fs/read "package.json")
         (jj/status)]))]
	(doseq [result results]
		(text result)))
```

Successful entries have `:status :fulfilled` and `:value`. Failed entries have `:status :rejected` and `:error {:message ...}`. Use `all` when any failed call should reject the group.

Strings print directly. Command and bash results print as a status line followed by raw output. Other collections print as EDN. The final non-nil value also prints.

Each `text` block gets a fair share of the output budget. Small blocks stay whole. A large block keeps its head and tail plus a marker with the exact `fs/read` call for its full text in the spill file, so one oversized block cannot hide the blocks around it. Error, image, and transaction notices are never clipped. Failure reports group repeated tool calls and omit interpreter stack frames. Reader errors show the source line where the unclosed form opens and where reading stopped. Unresolved or dotted symbols such as `tools.edit` and `git/status` get a hint. `text`, `println`, `prn`, and `image` return nil. Use image for image blocks, not text.

## Workstation capabilities

Semantic `jj`, `guix`, `repo`, `make`, `fs`, `sys`, and `search` namespaces use registered Pi tools with typed operation dispatch. JJ reads return records rather than formatted CLI output. Search and reads preserve bounded data from Pi's own implementations. Guix builds normalize store outputs; repo actions select verified Make targets. No git, rg, or generic process namespace is exposed. See [Workstation capabilities](domain-commands.md) for schemas, limits, cancellation, policy, and migration examples.

Use `fs/read-json` for complete JSON documents. `tools/read` returns display-limited text, which can be truncated and cannot safely feed `json/parse`. Use `fs/read-jsonl` with field projections and its continuation cursor for session reviews. The [structured read reference](domain-commands.md#structured-json-and-jsonl) defines limits and clipping flags. The [design explanation](structured-reads-design.md) records the alternatives.

Reduce tool results before printing. Discovery schemas, transcripts, and complete test logs do not belong in routine output. Store only cursors and compact summaries. A stored value is limited to 262144 JSON characters. The whole store is limited to 1048576.

## Measure agent friction

`node scripts/session-metrics.mjs [count | session.jsonl ...]` counts, per Pi session, codemode calls that use bash or `tools/read`, escaped bash maps, budget truncations, clipped blocks, parse and validation errors, and hints. It defaults to the seven most recent sessions. Run it before and after a guidance or capability change.

## Branch-local library

Explicit top-level `defsession` declarations persist functions and literal constants in `session`. Ordinary definitions remain temporary. Each invocation reconstructs the library in a fresh sandbox. Code and stored task data commit in one branch entry.

See [Persistent Clojure helpers](session-library.md) for syntax, inspection, forgetting, conflict handling, and recovery. [Branch-local SCI library design](library-design.md) records the extension-versus-fork decision.

## Discovery and conversion

Generated tool functions retain Pi's identifier spelling. Canonical tool IDs remain available through the catalog:

```clojure
(let [hit (first (await (catalog/search "lookup" {:limit 5})))
      detail (await (catalog/describe (:id hit)))]
	(text detail))
```

Inspect the schema before using `(await (catalog/invoke id args))`. `catalog/namespace` returns namespace instructions. Hidden tools and model-only tools are not callable. Deferred tools remain discoverable without appearing in the inline declaration list.

Tool arguments and results cross Pi's JSON bridge. JSON objects become keyword-keyed maps and arrays become vectors. Field names retain underscores and camelCase. String and keyword map keys encode to JSON keys. Keyword values encode to strings. Duplicate encoded keys fail. Functions, symbols, sets, regexes, and non-finite numbers cannot cross the bridge.

`json/parse` and `json/generate` use the same conversion rules.

## State, models, and failures

Every invocation gets a fresh SCI context. Only explicit `defsession` declarations survive through branch journal replay. Use small JSON-compatible values for stored task data:

```clojure
(store "cursor" {:page 2})
(load "cursor" {:page 1})
(unstore "cursor")
```

A missing load returns nil unless a default is supplied. Stored nil remains JSON null. Store changes commit only when evaluation succeeds. Pi restores them from entries on the active session branch.

Models use Pi's existing argument conventions with Lisp names:

- `models/get-models-of-type` lists known models.
- `models/get-available-of-type` lists authenticated models.
- `models/get-model-of-type` looks up a model.
- `models/classify` runs classification.
- `models/generate-images` generates images.

Await these calls. Check `:stopReason` and `:errorMessage`. Image blocks are in `:output`. Pi records usage through its original executor.

The options line retains JSON:

```clojure
;; @options: {"max_output_tokens": 2000, "timeout_ms": 60000}
(text "bounded output")
```

Pi's deadline, 256 MiB VM limit, output limits, and cancellation remain in force. Failed programs keep partial output. Earlier tool effects are not rolled back. Pending calls receive cancellation when the program ends.

## Implementation and constraints

`src/pi/library.cljs` owns declaration validation, replay, and library queries. `src/environment.ts` owns the versioned branch journal. `src/pi/sci.cljs` owns the restricted context and conversion. `src/source.ts` puts the compiled interpreter and program inside Pi's existing JavaScript executor. `src/catalog.ts` derives bindings and descriptions from callable capabilities. `src/extension.ts` adapts the exported `createCodemodeExtension` factory.

SCI is pinned to commit `5fe89056384418be50d232b4278344cb3a1a526b`. Its JIT is disabled. `src/pi/prepare.clj` applies checked build-time patches that sequence async body expressions and preserve source metadata. It never modifies the dependency cache. The original async behavior and the fixes have real QuickJS regression tests.

SCI cannot access JavaScript globals, arbitrary namespace loaders, filesystem APIs, processes, or networking. QuickJS remains the outer isolation boundary. Enabled tools such as bash still have their usual operating-system permissions. These tests are not a formal security proof.

`SCI-LICENSE` contains SCI's EPL 1.0 license. The compiled runtime also includes its ClojureScript dependencies under their upstream licenses.

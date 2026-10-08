# Workstation capabilities

SCI constructs arguments, transforms data, and controls execution. Registered Pi tools own effects. Trusted functions select typed operations rather than requiring the model to write CLI syntax. No git, rg, generic process, or shell namespace is added. Bash remains an escape hatch for host commands without a semantic capability and genuine shell features.

```clojure
(let [{:keys [items truncated]} (await (jj/log {:revisions "@" :limit 20}))]
  (text {:commits (mapv #(select-keys % [:change-id :description]) items)
         :truncated truncated}))

(await (jj/diff {:from "@-" :to "@" :paths ["desktop/build/pi-sci"]}))
(await (guix/build {:file "package.scm" :load_paths ["modules"]}))
(await (guix/system-build {:config "desktop/system.scm" :load_paths ["modules"]}))
(await (make/run "pi-sci-test"))
(await (repo/pi-sci-test))
(await (search/text "defsession" {:paths ["desktop/build/pi-sci"] :glob "*.cljs"}))
(await (search/files {:glob "*.clj" :path "desktop/build/pi-sci"}))
(await (fs/read "file.clj" {:start_line 20 :end_line 50}))
(await (fs/glob "/gnu/store/*-nss-certs-*/etc/ssl/certs/ca-certificates.crt"))
(await (sys/which ["ruff" "clang-format"]))
```

The codemode description lists one generated signature per callable function, such as `(search/files glob {:path :paths :limit})`. A key after `!` is required. SCI checks option keys before the call, so `(search/files {:pattern "*.clj"})` fails with the accepted keys instead of a schema union dump. A positional argument whose schema is an array also accepts one string, so `(sys/which "ruff")` and `(guix/lint "hello")` work.

## Registry and permissions

`src/commands.ts` gathers domain registries. Each operation owns its input schema, host implementation, positional shorthand, and mutation marker. Tool schemas, SCI bindings, and descriptions derive from those records. One registered Pi tool per domain accepts a discriminated `operation`. Hooks see that operation and all arguments before execution. They can block or redact the result.

There is no hidden host execution API available to submitted SCI code. The Node process backend is private. Aliases exist only when their underlying tool is callable, including explicit CLI allowlists. A CLI allowlist must include `jj`, `guix`, `make`, `repo`, `search`, `fs`, or `sys` when needed. Persistent helpers resolve current capabilities during replay; they retain no permissions or process handles.

Read-only operation markers describe intent, not a sandbox. JJ reads can snapshot working-copy changes. Make evaluates project code. Guix and user configuration can invoke external programs. Irreversible mutations still require operator permission. No automatic retries occur after external effects.

## Jujutsu

`jj/log` accepts `:revisions`, `:paths`, and `:limit`. Its default revisions are `@`. `jj/show` accepts a revision string or `{:revision "@"}`. Both return `:items` containing `:change-id`, `:commit-id`, `:description`, `:author`, `:committer`, `:parents`, and `:empty?`.

`jj/diff` returns file records with `:path`, `:status`, `:source`, `:before`, and `:after`. `jj/files-changed` returns path strings in `:items`. Both accept `:revisions` or `:from`/`:to`, plus `:paths` and `:limit`. Paths are quoted literal path prefixes, not fileset expressions. `jj/status` resolves the working-copy commit first, then reads its diff by commit ID. It adds `:working_copy` to the collection. `jj/file-show` accepts `:path` and optional `:revision`, and returns `:text` with command/truncation metadata.

`jj/patch` returns the textual `jj diff --git` patch in `:stdout`, or `--stat` output with `:stat true`. It takes the same `:revisions`, `:from`/`:to`, and `:paths` as `jj/diff`.

`jj/bookmark-list` and `jj/op-log` return bounded JSON records from JJ's serializer. `jj/version` returns textual command output. `jj/run` is a lower-level fixed-program fallback taking `:args`.

Mutations are `jj/new`, `jj/describe`, `jj/squash`, and `jj/rebase`. Describe takes `:message` and optional `:revision`, or a message string. New takes optional `:revisions` and `:message`. Squash requires a message and accepts `:from`/`:into`. Rebase requires `:source` and `:destination`. Explicit messages avoid interactive editors. Test mutations run only in temporary repositories.

Machine-readable reads validate JJ JSON before normalization. JJ's JSON format is version-sensitive; unsupported formats fail rather than falling back to display-text parsing. If transport truncation prevents complete JSON decoding, the call fails with the retained log path. Refine the query or reduce its limit. No partial JSON is presented as a complete result.

## Search and files

`search/text` and `search/files` use checked adaptations of Pi 1.0.4's own grep and find implementations. `scripts/prepare-search.mjs` verifies pinned source SHA-256 hashes, retains internal records before display formatting, and writes ignored build artifacts. See `PI-LICENSE`. Built-in Pi tools are not replaced. Their original schemas and output conventions remain available to existing callers.

Text search takes `:query`, optional `:path` or `:paths`, `:glob`, `:literal`, `:ignore_case`, and `:limit`. A query string can be followed by an options map. Records contain absolute `:path`, one-based `:line`, `:column`, `:columns`, `:column_unit "utf8-byte"`, and bounded `:text`. Results contain `:items`, `:root`, `:paths`, and `:truncated`. Pi's match/byte/line limits remain visible. Ignore files apply outside Git repositories, including JJ repositories. Explicit positive globs have Pi/ripgrep's normal override behavior for ignored files.

File search takes a glob string or `:glob`, optional `:path` or `:paths`, and `:limit`. Single-root results contain paths relative to `:root`. Multi-root results contain absolute paths. Multiple roots share the requested result budget. There is no fabricated continuation cursor; refine paths and patterns when truncated.

Pi's filename finder is line-oriented. Names containing newlines are not guaranteed to round-trip. Text search retains JSON match paths, including quotes and colons. Pi's existing search behavior for binary/non-UTF-8 paths remains a limitation.

`fs/read` uses Pi's read implementation and returns `:kind "text"`, absolute `:path`, clean `:text`, `:start_line`, `:end_line`, `:total_lines`, `:next_line`, and `:truncated`. Give `:start_line` plus `:end_line` or `:limit`, not both. Metadata follows Pi's line convention, including the final empty split line after a trailing newline. Oversized single lines return no complete line and no advancing continuation. Image reads keep Pi's processing. They return `:kind "image"` and an `:image` value suitable for `image` when processing succeeds, or `:kind "image-omitted"` and a `:note` when Pi omits the image. Do not forward an omitted image to `image`.

`fs/glob` expands an absolute or cwd-relative shell pattern segment by segment. It supports `*`, `?`, `[...]`, `[!...]`, and `**`. Like a shell, `*` skips dotfiles unless the segment starts with a dot. It reads no ignore files, includes directories, and returns sorted absolute paths with `:truncated` after 10000 visited directories or 100000 candidates. Use it for Guix store paths and `/tmp` build directories; use `search/files` for project trees that respect ignore files.

`fs/disk-usage` walks a path without following symlinks and returns `:bytes` (apparent size), `:disk_bytes` (allocated blocks), `:files`, `:directories`, and `:truncated` after 200000 entries.

`fs/stat` returns lstat metadata. `fs/exists?` returns a boolean and does not hide permission errors. `fs/list` takes optional `:path`, `:depth`, `:limit`, `:stat`, and `:sort`. `:stat true` adds `:size` and `:modified_ms`. `:sort` is `"name"`, `"modified"` (newest first), or `"size"` (largest first); it scans up to 10000 entries before sorting and reports `:truncated` when the scan stopped early. It does not recurse through symlinks. Listings are bounded by records, bytes, depth, and visited directories; order follows filesystem iteration. There is no unstable pagination token.

### Structured JSON and JSONL

`fs/read-json` reads and parses a complete regular file. It returns `:path`, `:value`, `:bytes`, and `:truncated false`. Input and serialized result each have a 1 MiB limit. Oversized input, malformed JSON, invalid UTF-8, and non-finite numbers fail. No partial document is returned.

```clojure
(let [r (await (fs/read-json "package.json"))]
	(text (:name (:value r))))
```

`fs/read-jsonl` parses complete records before projection. Each page returns `:path`, `:items`, `:next_cursor`, and `:truncated`. Items contain `:line`, `:value`, and `:value_truncated`. A non-nil cursor means more records remain. Pass the cursor unchanged to the next call. It contains a UTF-8 byte `:offset` and a physical `:line`. Blank JSON-whitespace lines are skipped, but still count toward physical line numbers. CRLF and a final record without a newline are supported.

`:fields` maps output names to vectors of property keys. Missing properties are omitted. Array elements can be selected with string index keys. Without `:fields`, the value contains the whole record. Strings clip recursively at `:max_string_chars`, which defaults to 2000 UTF-16 code units and accepts 0 through 16384. `:value_truncated true` marks clipping. Page `:truncated` reports continuation, not string clipping. Clipped values are excerpts, not complete source records.

```clojure
(let [options {:fields {:type ["type"] :role ["message" "role"]}
		:limit 100}
	page (await (fs/read-jsonl "session.jsonl" options))]
	(text (:items page))
	(when-let [cursor (:next_cursor page)]
		(text (:items (await (fs/read-jsonl "session.jsonl"
			(assoc options :cursor cursor)))))))
```

A record has a 2 MiB limit. A page scans at most 32 MiB and returns at most 48 KiB of item data. `:limit` defaults to 100 and accepts 1 through 1000. A projected item that exceeds the page bound fails with its line number. Select fewer fields or reduce the string limit. Invalid records fail with their path and line number. No record is silently skipped to meet a limit. Cursors resume from record boundaries without rescanning earlier bytes. Use them only against the same unchanged or append-only file. They do not provide snapshot isolation.

Follow every page before claiming full-file coverage. Reduce page data in SCI and print only the evidence needed. Session JSONL contains abandoned branches as well as the active branch. Full-file history is not the active conversation.

Search and filesystem operations accept `:cwd` and `:timeout_ms`. They reject meaningless `:env` options and use the invocation's cancellation signal. Their hooks use tool names `search` and `fs`, not built-in `grep`, `find`, or `read`; name-specific policies must account for these operation schemas.

## Host facts

`sys` is read-only. `sys/disk` returns `statfs` totals for a path. `sys/memory` reads `/proc/meminfo`. `sys/env` returns `:values` for `:names` or a `:prefix`; missing names map to nil, and values of names containing KEY, TOKEN, SECRET, PASSWORD, PASSWD, CREDENTIAL, COOKIE, or AUTH become `"[redacted]"`. `sys/which` returns `:found`, mapping each program to its first executable PATH entry or nil. `sys/processes` reads `/proc` and filters by a substring of the command name or command line. `sys/info` returns platform, release, architecture, hostname, uptime, CPU count, and load average.

## Guix and project actions

`guix/build` accepts `:file` or `:packages`/`:manifest`, with optional `:load_paths`, `:substitute_urls`, `:system`, `:dry_run`, and `:no_substitutes`. `guix/system-build` takes `:config`, optional load paths/substitute URLs, and dry-run. Successful complete builds add `:outputs` and `:outputs_complete`. Failed or truncated builds never certify outputs. No system activation operation is provided.

`guix/shell` requires an explicit noninteractive `:command` vector. It accepts packages/manifest, `:pure`, and `:container`. Weather accepts packages/manifest and substitute URLs. Describe, search, show, and version retain textual command results. `guix/download` fetches a URL into the store and adds `:store_path` and `:hash`. `guix/lint` takes `:packages`, `:load_paths`, and `:checkers`. `guix/style` takes `:packages` or `:files` (whole-file mode), `:load_paths`, `:styling`, and `:dry_run`; without `:dry_run` it rewrites files. `guix/run` remains a fixed-program fallback.

`make/run` takes a target string, vector, or map with `:targets`. Options include `:file`, `:jobs`, `:variables`, and lower-level `:args`. `make/dry-run` accepts targets/file. `make/targets` reads the Make database with built-in rules disabled and returns explicit target names. Evaluation can execute Makefile shell expressions even when recipes are not run. Version returns textual output.

`repo/check`, `repo/system-build`, `repo/home-build`, `repo/pi-sci-build`, `repo/pi-sci-test`, `repo/dusk-build`, and `repo/dusk-test` select verified targets in this Guix checkout. They reject unrelated working directories, validate the checkout marker, and execute at its root. `:dry_run` supports inspection. No ambiguous generic build/test action, stow, activation, or pull action is exposed.

## Command results and lifecycle

Command actions include `:ok`, `:exit_code`, `:signal`, `:stderr`, `:timed_out`, `:truncated`, `:full_output_path`, and `:duration_ms`. Textual results also contain `:stdout`. Named normalized reads omit raw stdout. Nonzero exits and deadlines return data for actions; spawn failures and aborts reject. Reads that cannot normalize complete machine output reject.

`text` prints a command or bash result as a status line, such as `[exit 0, 592 ms]`, followed by remaining fields as EDN, raw stdout, and stderr. Use `(text (pr-str r))` to see the whole map as data.

`result/check` checks command exit metadata and returns the result or throws. `result/stdout!` also requires a stdout string. Use structured fields for reads. Neither helper certifies completeness; inspect `:truncated` and build `:outputs_complete`. `result/check` returns its input. As the final non-nil expression, it prints the whole result. Check first, then print a bounded summary.

```clojure
(let [r (await (repo/pi-sci-test))]
	(result/check r)
	(text (select-keys r [:ok :exit_code :truncated])))
```

Command options accept `:cwd`, `:env`, and `:timeout_ms`. Arguments remain separate strings with no shell expansion or substitution. Commands have no stdin. Returned streams retain first/last 32768 characters at the 65536-character per-stream bound. Truncated output retains a private labeled full log, capped at 32 MiB. Exceeding the cap stops the command and fails. On POSIX, cancellation, deadline, and root completion clean up the owned process group with SIGKILL escalation. Windows terminates the immediate process only.

## Reusable SCI helpers

```clojure
(defsession ^:async changed-sci-files []
  (let [{:keys [items truncated]} (await (jj/files-changed))]
    (when truncated (throw (ex-info "Changed-file query was incomplete" {})))
    (filterv #(str/starts-with? % "desktop/build/pi-sci/") items)))

(await (session/changed-sci-files))
```

The [design](domain-commands-design.md) records alternatives and ownership. Deterministic execution tests verify capabilities and data contracts, not improved model choices. Reload Pi after changing both the frontend and SCI bundle. An incompatible cached binding descriptor fails with a reload instruction.

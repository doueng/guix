# Persistent Clojure helpers

## Declarations

A top-level `defsession` declares a function or literal value in `session`. Ordinary `def` and `defn` remain temporary.

```clojure
(defsession config {:prefix "project" :extensions [".clj" ".cljs"]})
(defsession source-file? [path]
	(some #(str/ends-with? path %) (:extensions config)))
(defsession ^:async read-upper [path]
	(str/upper-case (await (tools/read {:path path}))))
```

A later invocation uses those names through `session`:

```clojure
(text (session/source-file? "src/main.clj"))
(text (await (session/read-upper "README.md")))
```

Inside persisted functions, bare library names resolve in `session`. Qualified names use supplied namespaces and aliases. A single `fn` expression is also supported:

```clojure
(defsession increment (fn [n] (+ n 1)))
```

Functions have one parameter vector and can use destructuring or variadic arguments. Helpers containing `await` need `^:async`. Names start with an ASCII letter or underscore and are at most 128 characters. Names can also contain digits and `_!?*+<>=$%.-`. Runtime and inspection names are reserved.

Constants are JSON-compatible literal values. Computed initializers, persistent macros, dynamic vars, namespace mutation, and invocation-local references are unsupported. Function validation conservatively rejects namespace-changing forms even in quoted data. A helper cannot capture a temporary `user` definition or a surrounding local binding.

## Inspection and forgetting

```clojure
(text (session/definitions))
(text (session/source 'read-upper))
(session/forget 'read-upper)
```

`session/definitions` returns names, kinds, parameter vectors as strings, and async flags. `session/source` returns original declaration text or nil. Both name APIs accept symbols or strings, either bare or qualified with `session/`. Forgetting returns true for an active definition, otherwise false.

Declarations and forgetting take effect at their position in the program. Redefinition updates the existing SCI Var. Older helpers therefore see the new root value. Forgetting unbinds that Var rather than replacing captured references. Calling an unbound function fails. Unbound constants must not be used. SCI follows ClojureScript arithmetic semantics and can produce NaN when arithmetic uses an unbound value. Redefining the same name rebinds existing references.

The model receives a bounded library summary when a new prompt starts. The summary includes the branch revision and active definition count. Inspection returns full metadata and source on demand.

## Transactions and branches

Each invocation uses a fresh QuickJS worker and SCI context. Pi reconstructs the ordered library journal from the active branch. It disables output, storage, tool, discovery, and model effects during reconstruction.

Successful evaluation commits reached declarations, forget operations, and task-data writes in one `codemode-store` entry. A successful `exit` commits declarations already reached. It does not execute later declarations. Failures, cancellation, and deadlines commit neither code nor data.

The host checks the starting session, branch ancestry, library revision, and latest store entry before commit. A concurrent code or data write causes a transaction error. Completed external effects remain completed. The host never retries the program.

Branches inherit only ancestor declarations. Rewinds restore their ancestor library. Session-file reopening and conversation compaction reconstruct the same branch state. No interpreter object, previous permission, or invocation API reference persists.

Helpers compile against the currently callable capabilities. If a helper references a removed tool, reconstruction fails closed. Restoring that capability or explicitly resetting the library recovers the branch.

## Limits and recovery

The ordered journal permits 512 operations and 131072 source characters. Each declaration permits 16384 source characters. Forgetting does not reclaim journal capacity. Pi's VM deadline and memory budget include reconstruction. Its store transport limits also apply while staging the journal delta.

`/sci-library` displays a summary. `/sci-library reset` records an empty library on the current branch without changing task data. Reset does not evaluate old source. It can recover an incompatible journal or a removed capability. Branching to the reset's parent restores the previous journal.

Journal format version 1 carries the pinned SCI and library-ABI fingerprint. Incompatible journals error instead of loading a different evaluator or silently dropping definitions. Runtime changes that break declaration or replay semantics require a fingerprint change.

The [design explanation](library-design.md) describes commit capture and why this version does not fork Pi or retain live workers.

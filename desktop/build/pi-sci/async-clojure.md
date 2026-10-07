# Async Clojure in Pi

"Async Clojure" here means SCI's async/await support for this interpreter. It is not a requirement of Clojure as a language. It is not JVM Clojure's futures or `core.async`.

## Why tool calls need to wait

SCI runs inside Pi's QuickJS sandbox. A tool call leaves the sandbox and asks Pi to perform an operation. The operation may read a file, run a command, query an MCP server, or generate an image.

The injected tool function returns a promise immediately. Pi supplies the result later. `await` suspends the current computation until that promise completes. If the call fails, `await` throws at that point so a Clojure `try` can handle the error.

```clojure
(let [contents (await (tools/read {:path "package.json"}))]
	(text (str/upper-case contents)))
```

The call starts first. Evaluation resumes with the file contents. Without `await`, the binding holds a promise rather than the contents.

This wait must allow Pi's sandbox machinery to process the reply and settle the promise. A blocking loop on the JavaScript execution thread would not replace that mechanism.

## Ordinary computation stays synchronous

Arithmetic, collection operations, and pure helper functions do not need `await`.

```clojure
(defn double-value [n] (* 2 n))
(text (mapv double-value [1 2 3]))
```

The runner in `src/pi/sci.cljs` sequences submitted forms through async wrappers. It applies top-level `defsession` declarations between those forms. SCI transforms suspended evaluation into promise continuations. Unawaited intermediate calls do not suspend the next form.

A helper that contains `await` needs `^:async` so SCI performs that transformation on its body too. The helper returns a promise, which the caller awaits.

```clojure
(defn ^:async read-upper [path]
	(str/upper-case (await (tools/read {:path path}))))

(text (await (read-upper "package.json")))
```

The annotation does not start a thread.

## Concurrency is explicit

Two consecutive awaited calls run in sequence. To overlap independent operations, start both calls before awaiting their combined result.

```clojure
(let [[left right]
		(await (all [(tools/read {:path "left.txt"})
					(tools/read {:path "right.txt"})]))]
	(text [left right]))
```

Both reads start before the group completes. Pure Clojure work still runs on the interpreter's execution thread. `all-settled` collects each success or failure instead of rejecting the group on an error.

Pi cancels outstanding calls when the program finishes. Explicit waits keep dependent work in order and prevent the program from ending before required tool work completes.

## Why expose await

Explicit await matches Pi's existing promise-based tool bridge and SCI's supported async transformation. It makes sequential dependencies and concurrent groups visible in the program.

A different evaluator could hide the waits and give tools a blocking-looking interface. That is a separate interpreter design, not something Clojure itself forbids. This implementation keeps the existing Pi execution and cancellation contract instead.

`text`, `image`, `store`, `load`, and `unstore` are local synchronous bindings. Tool calls, catalog discovery, and model calls use promises.

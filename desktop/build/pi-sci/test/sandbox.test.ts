import assert from "node:assert/strict";
import { test } from "node:test";
import { CodemodeSandbox, type CodemodeSandboxOptions } from "@earendil-works/pi-codemode";
import { compileSciSource, parseSciSource } from "../src/source.ts";

async function evaluate(
	source: string,
	options: CodemodeSandboxOptions = {},
	store: Record<string, unknown> = {},
) {
	const sandbox = new CodemodeSandbox({
		timeoutMs: 10000,
		memoryLimitBytes: 256 * 1024 * 1024,
		...options,
	});
	try {
		return await sandbox.execute(compileSciSource(source), { store });
	} finally {
		await sandbox.close();
	}
}

for (const [source, expected] of [
	["(text (+ 1 2))", "3"],
	['(->> [{:name "b"} {:name "a"}] (map :name) sort vec)', '["a" "b"]'],
	['(:name (json/parse "{\\"name\\":\\"pi\\"}"))', "pi"],
	['(str/upper-case "pi")', "PI"],
	["(set/union #{1} #{2})", "#{1 2}"],
	["(let [[x y] [4 5]] (+ x y))", "9"],
	["(defn double-value [n] (* 2 n)) (text (mapv double-value [1 2 3]))", "[2 4 6]"],
	[
		'(defn ^:async read-upper [path] (str/upper-case (await (tools/read {:path path})))) (text (await (read-upper "package.json")))',
		"CONTENTS",
	],
	[
		'(let [[left right] (await (all [(tools/read {:path "left.txt"}) (tools/read {:path "right.txt"})]))] (text [left right]))',
		'["contents" "contents"]',
	],
	['(require \'[clojure.string :as s]) (s/upper-case "pi")', "PI"],
	[
		'(defn ^:async read-value [] (await (tools/read {:path "x"}))) (await (read-value))',
		"contents",
	],
	['(println "hello")', "hello"],
] as const) {
	test(`evaluates ${source}`, async () => {
		const result = await evaluate(source, { tools: [{ name: "read", execute: () => "contents" }] });
		assert.equal(result.ok, true, JSON.stringify(result));
		assert.deepEqual(result.output, [{ type: "text", text: expected }]);
	});
}

test("tool results use Clojure maps and exact field spelling", async () => {
	let args: unknown;
	const result = await evaluate(
		'(let [r (await (tools/bash {:command "x"}))] [(:exit_code r) (:mimeType r)])',
		{
			tools: [
				{
					name: "bash",
					execute: (input) => {
						args = input;
						return { exit_code: 7, mimeType: "image/png" };
					},
				},
			],
		},
	);
	assert.equal(result.ok, true, JSON.stringify(result));
	assert.deepEqual(args, { command: "x" });
	assert.deepEqual(result.output, [{ type: "text", text: '[7 "image/png"]' }]);
});

test("await composes inside macros, loops, and caught tool errors", async () => {
	const result = await evaluate(
		`
		(loop [n 0 total 0]
			(if (< n 3)
				(recur (inc n) (+ total (await (tools/value {:n n}))))
				(text total)))
		(try (await (tools/fail {}))
			(catch :default e (text "caught")))
	`,
		{
			tools: [
				{ name: "value", execute: (args) => (args as { n: number }).n + 1 },
				{
					name: "fail",
					execute: () => {
						throw new Error("expected failure");
					},
				},
			],
		},
	);
	assert.equal(result.ok, true, JSON.stringify(result));
	assert.deepEqual(result.output, [
		{ type: "text", text: "6" },
		{ type: "text", text: "caught" },
	]);
});

test("all and all-settled realize eager parallel calls", async () => {
	let pending = 0;
	let maximum = 0;
	const result = await evaluate(
		`
		(text (await (all [(tools/value {:n 1}) (tools/value {:n 2})])))
		(text (await (all-settled [(tools/value {:n 3}) (tools/fail {})])))
	`,
		{
			tools: [
				{
					name: "value",
					execute: async (args) => {
						maximum = Math.max(maximum, ++pending);
						await new Promise((resolve) => setTimeout(resolve, 5));
						pending--;
						return (args as { n: number }).n;
					},
				},
				{
					name: "fail",
					execute: () => {
						throw new Error("bad");
					},
				},
			],
		},
	);
	assert.equal(result.ok, true, JSON.stringify(result));
	assert.equal(maximum, 2);
	assert.deepEqual(result.output, [
		{ type: "text", text: "[1 2]" },
		{
			type: "text",
			text: '[{:status :fulfilled, :value 3} {:status :rejected, :error {:message "bad"}}]',
		},
	]);
});

test("store preserves null, distinguishes missing defaults, and deletes explicitly", async () => {
	const result = await evaluate(
		`
		(store "null" nil)
		(store "object" {:exit_code 2 :choice :yes})
		(unstore "old")
		(text [(load "null" "missing") (load "missing" "default") (load "object")])
	`,
		{},
		{ old: true },
	);
	assert.equal(result.ok, true, JSON.stringify(result));
	if (!result.ok) return;
	assert.deepEqual(result.storeWrites, {
		set: { null: null, object: { exit_code: 2, choice: "yes" } },
		delete: ["old"],
	});
	assert.deepEqual(result.output, [
		{ type: "text", text: '[nil "default" {:exit_code 2, :choice "yes"}]' },
	]);
});

test("failure keeps output and real effects, but discards store writes", async () => {
	let effect = "before";
	const result = await evaluate(
		'(store "x" 1) (await (tools/change {})) (text "partial") (throw (ex-info "bad" {}))',
		{
			tools: [
				{
					name: "change",
					execute: () => {
						effect = "after";
						return null;
					},
				},
			],
		},
	);
	assert.equal(result.ok, false);
	assert.equal(effect, "after");
	assert.deepEqual(result.output, [{ type: "text", text: "partial" }]);
	assert.equal("storeWrites" in result, false);
});

for (const source of [
	'(tools/value {:a 1 "a" 2})',
	'(store "x" #{1})',
	'(store "x" (fn [] 1))',
	'(store "x" \'symbol)',
	'(store "x" #"regex")',
	'(store "x" ##NaN)',
]) {
	test(`rejects non-JSON data ${source}`, async () => {
		const result = await evaluate(source, {
			tools: [{ name: "value", execute: () => "unexpected" }],
		});
		assert.equal(result.ok, false, JSON.stringify(result));
		assert.match(!result.ok ? result.error.message : "", /JSON|finite/);
	});
}

for (const source of [
	'(js/eval "1")',
	'(js/Function. "return 1")',
	"(js/process.cwd)",
	'(js/fetch "https://example.com")',
	'(slurp "/etc/passwd")',
	'(spit "/tmp/no" "x")',
	"(eval '(+ 1 2))",
	'(load-string "(+ 1 2)")',
	"(require '[clojure.java.shell :as shell])",
	'(require "node:fs")',
	"(require '[sci.core :as sci])",
	"(.-constructor tools/read)",
]) {
	test(`denies ambient capability ${source}`, async () => {
		const result = await evaluate(source, { tools: [{ name: "read", execute: () => "contents" }] });
		assert.equal(result.ok, false, JSON.stringify(result));
		assert.equal(!result.ok ? result.error.kind : "", "script");
	});
}

test("fresh invocations cannot observe prior definitions", async () => {
	const first = await evaluate("(def secret 42) (text secret)");
	assert.equal(first.ok, true, JSON.stringify(first));
	assert.deepEqual(first.output, [{ type: "text", text: "42" }]);
	const second = await evaluate("secret");
	assert.equal(second.ok, false);
	assert.match(!second.ok ? second.error.message : "", /secret/);
});

test("exit succeeds and keeps output and store changes", async () => {
	const result = await evaluate('(store "x" 1) (text "kept") (exit) (text "unreachable")');
	assert.equal(result.ok, true, JSON.stringify(result));
	assert.deepEqual(result.output, [{ type: "text", text: "kept" }]);
	if (result.ok) assert.deepEqual(result.storeWrites, { set: { x: 1 }, delete: [] });
});

test("infinite Lisp loops time out without poisoning later calls", async () => {
	const result = await evaluate("(loop [] (recur))", { timeoutMs: 1000 });
	assert.equal(!result.ok ? result.error.kind : "", "timeout");
	const next = await evaluate("(+ 2 3)");
	assert.deepEqual(next.output, [{ type: "text", text: "5" }]);
});

test("memory exhaustion is contained in the VM", async () => {
	const result = await evaluate("(loop [xs []] (recur (conj xs (vec (range 10000)))))", {
		memoryLimitBytes: 32 * 1024 * 1024,
	});
	assert.equal(result.ok, false);
	assert.match(!result.ok ? result.error.message : "", /memory/);
});

test("pending calls are cancelled when the body ends", async () => {
	let cancelled = false;
	const result = await evaluate('(tools/slow {}) (text "finished")', {
		tools: [
			{
				name: "slow",
				execute: (_args, { signal }) =>
					new Promise((_resolve, reject) => {
						signal.addEventListener("abort", () => {
							cancelled = true;
							reject(new Error("cancelled"));
						});
					}),
			},
		],
	});
	assert.equal(result.ok, true, JSON.stringify(result));
	assert.equal(cancelled, true);
	assert.deepEqual(
		result.calls.map((call) => [call.name, call.status]),
		[["slow", "cancelled"]],
	);
	assert.deepEqual(result.output, [{ type: "text", text: "finished" }]);
});

test("async helpers sequence their own body expressions", async () => {
	const result = await evaluate(
		`
		(defn ^:async helper []
			(text (await (tools/read {})))
			(text "after"))
		(await (helper))
	`,
		{
			tools: [
				{
					name: "read",
					execute: async () => {
						await new Promise((resolve) => setTimeout(resolve, 5));
						return "before";
					},
				},
			],
		},
	);
	assert.equal(result.ok, true, JSON.stringify(result));
	assert.deepEqual(result.output, [
		{ type: "text", text: "before" },
		{ type: "text", text: "after" },
	]);
});

test("errors point at the original Clojure source line", async () => {
	const result = await evaluate('(text "first")\n(unknown-function)');
	assert.equal(result.ok, false);
	assert.match(!result.ok ? result.error.message : "", /codemode.clj:2:/);
});

test("abort stops evaluation and cancels in-flight tools", async () => {
	let started!: () => void;
	let cancelled = false;
	const ready = new Promise<void>((resolve) => {
		started = resolve;
	});
	const sandbox = new CodemodeSandbox({
		tools: [
			{
				name: "slow",
				execute: (_args, { signal }) =>
					new Promise((_resolve, reject) => {
						started();
						signal.addEventListener("abort", () => {
							cancelled = true;
							reject(new Error("cancelled"));
						});
					}),
			},
		],
	});
	const controller = new AbortController();
	try {
		const pending = sandbox.execute(compileSciSource("(await (tools/slow {}))"), {
			signal: controller.signal,
		});
		await ready;
		controller.abort();
		const result = await pending;
		assert.equal(!result.ok ? result.error.kind : "", "aborted");
		assert.equal(cancelled, true);
		assert.deepEqual(
			result.calls.map((call) => [call.name, call.status]),
			[["slow", "cancelled"]],
		);
	} finally {
		await sandbox.close();
	}
});

test("store size limits apply to encoded Clojure values", async () => {
	const result = await evaluate('(store "big" (apply str (repeat 300000 "x")))');
	assert.equal(result.ok, false);
	assert.match(!result.ok ? result.error.message : "", /store.*characters|store.*large/);
});

test("source options preserve the source's line numbering", () => {
	assert.deepEqual(
		parseSciSource(';; @options: {"max_output_tokens": 20, "timeout_ms": 1000}\n(text "x")'),
		{
			code: '\n(text "x")',
			options: { maxOutputTokens: 20, timeoutMs: 1000 },
		},
	);
	for (const source of [
		"",
		";; @options: {}",
		';; @options: {"unknown":1}\n1',
		';; @options: {"timeout_ms":0}\n1',
	]) {
		assert.throws(() => parseSciSource(source));
	}
});

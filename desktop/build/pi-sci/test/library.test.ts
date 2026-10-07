import assert from "node:assert/strict";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { CodemodeSandbox } from "@earendil-works/pi-codemode";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import {
	ENVIRONMENT_KEY,
	ENVIRONMENT_RUNTIME,
	environmentDefinitions,
	readEnvironment,
} from "../src/environment.ts";
import { compileSciSource } from "../src/source.ts";
import { harness, output } from "./harness.ts";

test("branch-local helpers and values survive fresh invocations with introspectable source", async () => {
	const runtime = await harness();
	try {
		const first = await runtime.call(
			"(defsession x 1) (defsession f [n] (+ n x)) (defsession ^:async read-upper [p] (str/upper-case (await (tools/read {:path p}))))",
		);
		assert.equal(first.isError, false, output(first));
		writeFileSync(join(runtime.cwd, "value.txt"), "hello");
		const second = await runtime.call(
			'(text (session/f 2)) (text (session/source \'f)) (text (mapv :name (session/definitions))) (text (await (session/read-upper "value.txt")))',
		);
		assert.equal(second.isError, false, output(second));
		assert.match(output(second), /3/);
		assert.match(output(second), /HELLO/);
		assert.match(output(second), /defsession f/);
		assert.match(output(second), /session\/read-upper/);
		assert.deepEqual(
			environmentDefinitions(readEnvironment(runtime.manager.getBranch())).map((item) => item.name),
			["f", "read-upper", "x"],
		);
		const redefined = await runtime.call("(defsession x 2) (text (session/f 0))");
		assert.equal(redefined.isError, false, output(redefined));
		assert.match(output(redefined), /2/);
		assert.match(output(await runtime.call("(session/f 0)")), /2/);
		assert.equal((await runtime.call("(session/forget 'x)")).isError, false);
		assert.deepEqual(
			environmentDefinitions(readEnvironment(runtime.manager.getBranch())).map((item) => item.name),
			["f", "read-upper"],
		);
		assert.match(
			output(await runtime.call("(session/source 'x) (text (nil? (session/source 'x)))")),
			/true/,
		);
		assert.equal((await runtime.call("(defsession x 7)")).isError, false);
		assert.match(output(await runtime.call("(session/f 0)")), /7/);
		assert.equal((await runtime.call("(session/forget 'f)")).isError, false);
		assert.equal((await runtime.call("(session/f 0)")).isError, true);
	} finally {
		runtime.close();
	}
});

test("literal collections and fn expressions replay with destructuring, variadic args, and async metadata", async () => {
	const runtime = await harness();
	try {
		writeFileSync(join(runtime.cwd, "value.txt"), "value");
		const first = await runtime.call(
			"(defsession values {:Mixed_case [nil true :keyword]}) (defsession sum (fn [[a b] & more] (reduce + (+ a b) more))) (defsession ^:async read-value (fn [p] (await (tools/read {:path p}))))",
		);
		assert.equal(first.isError, false, output(first));
		const second = await runtime.call(
			'(text (:Mixed_case session/values)) (text (session/sum [1 2] 3 4)) (text (await (session/read-value "value.txt")))',
		);
		assert.equal(second.isError, false, output(second));
		assert.match(output(second), /\[nil true :keyword\]/);
		assert.match(output(second), /10/);
		assert.match(output(second), /value/);
	} finally {
		runtime.close();
	}
});

test("code and data use one entry, branch together, and survive compaction and reopening", async () => {
	const runtime = await harness([], true);
	try {
		const first = await runtime.call('(defsession x 1) (store "cursor" 1)');
		assert.equal(first.isError, false, output(first));
		const entries = runtime.manager
			.getBranch()
			.filter((entry) => entry.type === "custom" && entry.customType === "codemode-store");
		assert.equal(entries.length, 1);
		assert.deepEqual(entries[0]?.type === "custom" ? entries[0].data : undefined, {
			set: { cursor: 1 },
			delete: [],
			sciEnvironment: {
				version: 1,
				runtime: ENVIRONMENT_RUNTIME,
				reset: false,
				operations: [
					{
						op: "define",
						name: "x",
						source: "(defsession x 1)",
						kind: "value",
						parameters: null,
						async: false,
					},
				],
			},
		});
		const root = runtime.manager.getLeafId();
		assert.ok(root);
		assert.equal(
			(await runtime.call('(defsession x 2) (defsession f [] x) (store "cursor" 2)')).isError,
			false,
		);
		runtime.manager.branch(root);
		const branch = await runtime.call('(text [session/x (load "cursor")])');
		assert.equal(branch.isError, false, output(branch));
		assert.match(output(branch), /\[1 1\]/);
		assert.equal((await runtime.call("(session/f)")).isError, true);
		runtime.manager.appendCompaction("summarized", root, 100);
		const path = runtime.manager.getSessionFile();
		assert.ok(path);
		const reopened = SessionManager.open(path);
		assert.deepEqual(
			environmentDefinitions(readEnvironment(reopened.getBranch())).map((item) => item.name),
			["x"],
		);
		runtime.manager.setSessionFile(path);
		assert.match(output(await runtime.call('(text [session/x (load "cursor")])')), /\[1 1\]/);
	} finally {
		runtime.close();
	}
});

test("failure, deadline, abort, and successful exit have transactional declaration semantics", async () => {
	let started: () => void = () => {};
	const ready = new Promise<void>((resolve) => {
		started = resolve;
	});
	const runtime = await harness([
		(pi) => {
			pi.registerTool({
				name: "wait",
				label: "wait",
				description: "Wait until abort",
				exposure: "codemode",
				parameters: Type.Object({}),
				execute: async (_id, _args, signal) => {
					started();
					await new Promise<void>((_resolve, reject) =>
						signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true }),
					);
					return { content: [], details: undefined };
				},
			});
		},
	]);
	try {
		assert.equal((await runtime.call('(defsession x 1) (store "cursor" 1)')).isError, false);
		const failed = await runtime.call(
			'(defsession x 2) (store "cursor" 2) (text "partial") (throw (ex-info "fail" {}))',
		);
		assert.equal(failed.isError, true);
		assert.match(output(failed), /partial/);
		const timed = await runtime.call(
			';; @options: {"timeout_ms": 1000}\n(defsession x 3) (store "cursor" 3) (loop [] (recur))',
		);
		assert.equal(timed.isError, true);
		const waiting = runtime.call('(defsession x 4) (store "cursor" 4) (await (tools/wait {}))');
		await ready;
		await runtime.session.abort();
		await waiting;
		assert.match(output(await runtime.call('(text [session/x (load "cursor")])')), /\[1 1\]/);
		const exited = await runtime.call(
			'(defsession x 5) (store "cursor" 5) (exit) (defsession never 1)',
		);
		assert.equal(exited.isError, false, output(exited));
		assert.match(output(await runtime.call('(text [session/x (load "cursor")])')), /\[5 5\]/);
		assert.equal((await runtime.call("session/never")).isError, true);
	} finally {
		runtime.close();
	}
});

for (const source of [
	'(defsession x (await (tools/bash {:command "printf forbidden"})))',
	'(defsession x (do (text "forbidden") 1))',
	'(defsession x (load "cursor"))',
	"(def local 2) (defsession f [] user/local)",
	"(let [local 2] (defsession f [] local))",
	"(defsession ^:macro f [x] x)",
	"(defsession f [] (def x 1))",
	"(defsession f [] (require '[clojure.string :as s]))",
	"(defsession source 1)",
	'(defsession x {:a 1 "a" 2})',
	"(defsession x 1) (def session/x 2)",
	`(store "${ENVIRONMENT_KEY}" {})`,
]) {
	test(`rejects unsafe declaration or reserved state ${source}`, async () => {
		const runtime = await harness();
		try {
			const result = await runtime.call(source);
			assert.equal(result.isError, true, output(result));
			assert.deepEqual(readEnvironment(runtime.manager.getBranch()).operations, []);
			assert.doesNotMatch(output(result), /\nforbidden\n/);
		} finally {
			runtime.close();
		}
	});
}

test("trusted declaration lowering and async wrappers ignore shadowed core macro names", async () => {
	const runtime = await harness();
	try {
		const result = await runtime.call(
			'(defsession defn [n args body] (text "BAD MACRO")) (defsession f [] 1) (def fn 2) (text (session/f))',
		);
		assert.equal(result.isError, false, output(result));
		assert.doesNotMatch(output(result), /BAD MACRO/);
		assert.match(output(result), /1/);
		const replayed = await runtime.call("(text (session/f))");
		assert.equal(replayed.isError, false, output(replayed));
		assert.match(output(replayed), /1/);
		assert.doesNotMatch(output(replayed), /BAD MACRO/);
	} finally {
		runtime.close();
	}
});

test("replay rejects stored computed initializers before any output or external effect", async () => {
	let effects = 0;
	const sandbox = new CodemodeSandbox({
		tools: [
			{
				name: "effect",
				execute: () => {
					effects++;
					return "ran";
				},
			},
		],
	});
	try {
		const result = await sandbox.execute(
			compileSciSource('(text "BODY RAN")', [], {
				revision: "test",
				operations: [
					{
						op: "define",
						name: "x",
						source: '(defsession x (do (text "REPLAY RAN") (tools/effect {}) 1))',
						kind: "value",
						parameters: null,
						async: false,
					},
				],
			}),
		);
		assert.equal(result.ok, false, JSON.stringify(result));
		assert.deepEqual(result.output, []);
		assert.equal(effects, 0);
	} finally {
		await sandbox.close();
	}
});

test("journal capacity prevents new declarations without losing old code or data", async () => {
	const runtime = await harness();
	try {
		await runtime.call('(store "cursor" 1)');
		runtime.manager.appendCustomEntry("codemode-store", {
			set: {},
			delete: [],
			sciEnvironment: {
				version: 1,
				runtime: ENVIRONMENT_RUNTIME,
				reset: false,
				operations: Array.from({ length: 512 }, () => ({
					op: "define",
					name: "x",
					source: "(defsession x 1)",
					kind: "value",
					parameters: null,
					async: false,
				})),
			},
		});
		const result = await runtime.call('(defsession y 2) (store "cursor" 2)');
		assert.equal(result.isError, true, output(result));
		assert.match(output(result), /library limit/);
		assert.match(output(await runtime.call('(text [session/x (load "cursor")])')), /\[1 1\]/);
		await runtime.session.prompt("/sci-library reset");
		assert.equal((await runtime.call("(defsession y 2)")).isError, false);
	} finally {
		runtime.close();
	}
});

test("data-only concurrent commits also invalidate a stale transaction", async () => {
	const runtime = await harness([
		(pi) => {
			pi.registerTool({
				name: "change-data",
				label: "change-data",
				description: "Concurrent task data",
				exposure: "codemode",
				parameters: Type.Object({}),
				execute: async () => {
					pi.appendEntry("codemode-store", { set: { cursor: 9 }, delete: [] });
					return { content: [], details: undefined };
				},
			});
		},
	]);
	try {
		const result = await runtime.call(
			'(defsession x 2) (store "cursor" 2) (await (tools/change_data {}))',
		);
		assert.equal(result.isError, true, output(result));
		assert.match(output(result), /Script failed/);
		assert.deepEqual(readEnvironment(runtime.manager.getBranch()).operations, []);
		assert.match(output(await runtime.call('(text (load "cursor"))')), /9/);
	} finally {
		runtime.close();
	}
});

test("concurrent library revisions cannot commit stale code or data or retry effects", async () => {
	let effects = 0;
	const runtime = await harness([
		(pi) => {
			pi.registerTool({
				name: "change",
				label: "change",
				description: "Simulate another environment writer",
				exposure: "codemode",
				parameters: Type.Object({}),
				execute: async () => {
					effects++;
					pi.appendEntry("codemode-store", {
						set: {},
						delete: [],
						sciEnvironment: {
							version: 1,
							runtime: ENVIRONMENT_RUNTIME,
							reset: false,
							operations: [
								{
									op: "define",
									name: "x",
									source: "(defsession x 9)",
									kind: "value",
									parameters: null,
									async: false,
								},
							],
						},
					});
					return { content: [{ type: "text", text: "changed" }], details: undefined };
				},
			});
		},
	]);
	try {
		assert.equal((await runtime.call('(defsession x 1) (store "cursor" 1)')).isError, false);
		const result = await runtime.call(
			'(defsession x 2) (store "cursor" 2) (await (tools/change {}))',
		);
		assert.equal(result.isError, true, output(result));
		assert.match(output(result), /environment changed/);
		assert.equal(effects, 1);
		assert.match(output(await runtime.call('(text [session/x (load "cursor")])')), /\[9 1\]/);
	} finally {
		runtime.close();
	}
});

test("branch switches during tool effects prevent committing to the new branch", async () => {
	let branch: () => void = () => {};
	const runtime = await harness([
		(pi) => {
			pi.registerTool({
				name: "branch",
				label: "branch",
				description: "Switch the branch",
				exposure: "codemode",
				parameters: Type.Object({}),
				execute: async () => {
					branch();
					return { content: [{ type: "text", text: "branched" }], details: undefined };
				},
			});
		},
	]);
	try {
		const root = runtime.manager.appendCustomEntry("root", {});
		await runtime.call("(defsession x 1)");
		branch = () => runtime.manager.branch(root);
		const result = await runtime.call(
			'(defsession x 2) (store "cursor" 2) (await (tools/branch {}))',
		);
		assert.equal(result.isError, true, output(result));
		assert.match(output(result), /branch or environment changed/);
		assert.deepEqual(readEnvironment(runtime.manager.getBranch()).operations, []);
	} finally {
		runtime.close();
	}
});

test("replayed declarations cannot acquire a capability that is no longer callable", async () => {
	const runtime = await harness([
		(pi) => {
			pi.registerTool({
				name: "special",
				label: "special",
				description: "Capability",
				parameters: Type.Object({}),
				execute: async () => ({
					content: [{ type: "text", text: "SPECIAL EFFECT" }],
					details: undefined,
				}),
			});
			pi.registerCommand("remove-special", {
				description: "Remove fixture capability",
				handler: async () => {
					pi.setActiveTools(pi.getActiveTools().filter((name) => name !== "special"));
				},
			});
		},
	]);
	try {
		assert.equal(
			(await runtime.call("(defsession ^:async special [] (await (tools/special {})))")).isError,
			false,
		);
		await runtime.session.prompt("/remove-special");
		const result = await runtime.call("(await (session/special))");
		assert.equal(result.isError, true, output(result));
		assert.doesNotMatch(output(result), /SPECIAL EFFECT/);
	} finally {
		runtime.close();
	}
});

test("explicit recovery clears incompatible code while preserving task data", async () => {
	const runtime = await harness();
	try {
		await runtime.call('(store "cursor" 7)');
		runtime.manager.appendCustomEntry("codemode-store", {
			set: {},
			delete: [],
			sciEnvironment: { version: 999 },
		});
		assert.equal((await runtime.call('(text "MUST NOT RUN")')).isError, true);
		await runtime.session.prompt("/sci-library reset");
		assert.deepEqual(readEnvironment(runtime.manager.getBranch()).operations, []);
		const result = await runtime.call('(text (load "cursor"))');
		assert.equal(result.isError, false, output(result));
		assert.match(output(result), /7/);
	} finally {
		runtime.close();
	}
});

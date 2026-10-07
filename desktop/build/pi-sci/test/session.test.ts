import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { type ExtensionAPI, SessionManager } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { harness, output } from "./harness.ts";
import { USAGE } from "./provider.ts";

test("the real Pi session executes read, bash, edit, and write through SCI", async () => {
	const runtime = await harness();
	try {
		const result = await runtime.call(`
			(await (tools/write {:path "value.txt" :content "before"}))
			(await (tools/edit {:path "value.txt" :edits [{:oldText "before" :newText "after"}]}))
			(text (await (tools/read {:path "value.txt"})))
			(let [r (await (tools/bash {:command "printf shell"}))]
				(text [(:exit_code r) (:output r)]))
		`);
		assert.equal(result.isError, false, output(result));
		assert.match(output(result), /after/);
		assert.match(output(result), /\[0 "shell"\]/);
		assert.equal(readFileSync(join(runtime.cwd, "value.txt"), "utf8"), "after");
	} finally {
		runtime.close();
	}
});

test("nested Pi guards and result redaction apply to SCI calls", async () => {
	const seen: string[] = [];
	const runtime = await harness([
		(pi: ExtensionAPI) => {
			pi.on("tool_call", (event) => {
				if (event.parentToolCallId) seen.push(event.toolName);
				if (event.toolName === "bash") return { block: true, reason: "shell denied" };
			});
			pi.on("tool_result", (event) => {
				if (event.toolName === "read" && !event.isError)
					return { content: [{ type: "text", text: "redacted" }], details: undefined };
			});
		},
	]);
	try {
		writeFileSync(join(runtime.cwd, "secret.txt"), "secret");
		const read = await runtime.call('(text (await (tools/read {:path "secret.txt"})))');
		assert.match(output(read), /redacted/);
		assert.doesNotMatch(output(read), /secret/);
		const blocked = await runtime.call('(await (tools/bash {:command "printf forbidden"}))');
		assert.equal(blocked.isError, true);
		assert.match(output(blocked), /shell denied/);
		assert.deepEqual(seen, ["read", "bash"]);
		const invalid = await runtime.call("(await (tools/read {}))");
		assert.equal(invalid.isError, true);
		assert.match(output(invalid), /path|invalid|Invalid/);
	} finally {
		runtime.close();
	}
});

test("store writes follow the real session branch and survive reconstruction", async () => {
	const runtime = await harness();
	try {
		const first = await runtime.call('(store "value" {:n 1})');
		assert.equal(first.isError, false, output(first));
		const root = runtime.manager.getLeafId();
		assert.ok(root, "successful storage has a session entry");
		await runtime.call('(store "value" {:n 2})');
		assert.match(output(await runtime.call('(load "value")')), /\{:n 2\}/);
		runtime.manager.branch(root);
		assert.match(output(await runtime.call('(load "value")')), /\{:n 1\}/);
		const failed = await runtime.call('(store "value" {:n 9}) (throw (ex-info "no" {}))');
		assert.equal(failed.isError, true);
		assert.match(output(await runtime.call('(load "value")')), /\{:n 1\}/);
		const branch = runtime.manager
			.getBranch()
			.filter((entry) => entry.type === "custom" && entry.customType === "codemode-store");
		assert.equal(branch.length, 1);
	} finally {
		runtime.close();
	}
});

test("catalog uses only callable tools and resolves canonical IDs", async () => {
	const runtime = await harness([
		(pi) => {
			for (const [name, exposure] of [
				["mcp__demo__lookup", "deferred"],
				["9lookup", "deferred"],
				["private", "hidden"],
				["ask", "model-only"],
			] as const) {
				pi.registerTool({
					name,
					exposure,
					label: name,
					description: "Find a demo record.",
					promptGuidelines: ["A fixture lookup must use its declared key."],
					namespace: { name: "mcp__demo", instructions: "Inspect the schema first." },
					parameters: Type.Object({ key: Type.String() }),
					execute: async (_id, args) => ({
						content: [{ type: "text", text: `record ${args.key}` }],
						details: undefined,
					}),
				});
			}
		},
	]);
	try {
		const result = await runtime.call(`
			(let [hits (await (catalog/search "demo lookup" {:namespace "demo"}))
			      hit (first hits)
			      detail (await (catalog/describe (:id hit)))]
				(text [(:id detail) (:symbol detail)])
				(text (:description detail))
				(text (await (catalog/invoke (:id detail) {:key "x"}))))
			(text (:instructions (await (catalog/namespace "demo"))))
		`);
		assert.equal(result.isError, false, output(result));
		assert.match(output(result), /\["mcp__demo__lookup" "tools\/mcp__demo__lookup"\]/);
		assert.match(output(result), /record x/);
		assert.match(output(result), /A fixture lookup must use its declared key/);
		assert.match(output(result), /Inspect the schema first/);
		const unusual = await runtime.call('(await (catalog/invoke "9lookup" {:key "digit"}))');
		assert.equal(unusual.isError, false, output(unusual));
		assert.match(output(unusual), /record digit/);
		const hidden = await runtime.call('(await (catalog/invoke "private" {:key "x"}))');
		assert.equal(hidden.isError, true);
		assert.match(output(hidden), /not callable/);
		const nested = await runtime.call('(await (catalog/invoke "codemode" {:code "1"}))');
		assert.equal(nested.isError, true);
		assert.match(output(nested), /not callable/);
	} finally {
		runtime.close();
	}
});

for (const language of [undefined, "javascript", "not-a-language"]) {
	test(`obsolete language setting ${language ?? "omitted"} cannot enable JavaScript`, async () => {
		const runtime = await harness([], false, {
			mode: "only",
			...(language === undefined ? {} : { language }),
		});
		try {
			const success = await runtime.call('(store "x" 42) (text (load "x"))');
			assert.equal(success.isError, false, output(success));
			assert.match(output(success), /42/);
			const rejected = await runtime.call('store("x", 99); text("JS EXECUTED");');
			assert.equal(rejected.isError, true, output(rejected));
			assert.doesNotMatch(output(rejected), /JS EXECUTED/);
			assert.match(output(await runtime.call('(load "x")')), /42/);
		} finally {
			runtime.close();
		}
	});
}

test("Lisp options control the actual Pi deadline and output budget", async () => {
	const runtime = await harness();
	try {
		const timed = await runtime.call(';; @options: {"timeout_ms": 1000}\n(loop [] (recur))');
		assert.equal(timed.isError, true);
		assert.match(output(timed), /timed out/);
		const limited = await runtime.call(
			';; @options: {"max_output_tokens": 20}\n(text (apply str (repeat 1000 "x")))',
		);
		assert.equal(limited.isError, false, output(limited));
		const path = (limited.details as { fullOutputPath: string }).fullOutputPath;
		assert.equal(readFileSync(path, "utf8").includes("x".repeat(1000)), true);
	} finally {
		runtime.close();
	}
});

test("classifier and image calls preserve model discovery, accounting, and images", async () => {
	const runtime = await harness();
	try {
		const result = await runtime.call(`
			(let [available (await (models/get-available-of-type "classifier" "sci-fixture"))
			      judge (first available)
			      answer (await (models/classify judge
			        {:state {:item "x"} :questions {:ok {:type :bool :instructions "Is this accepted?" :criteria {:true "yes" :false "no"}}}}))
			      painter (await (models/get-model-of-type "image" "sci-fixture" "painter"))
			      picture (await (models/generate-images painter {:input [{:type :text :text "one pixel"}]}))]
				(text [(:stopReason answer) (get-in answer [:answers :ok :probability])])
				(doseq [block (:output picture)] (image block)))
		`);
		assert.equal(result.isError, false, output(result));
		assert.match(output(result), /\["stop" 0.75\]/);
		assert.equal(result.content.filter((block) => block.type === "image").length, 1);
		assert.equal(result.usage?.totalTokens, 24);
		assert.equal(result.usage?.cost.total, USAGE.cost.total * 2);
		assert.doesNotMatch(output(result), /iVBOR/);
	} finally {
		runtime.close();
	}
});

test("persisted store can be read after reopening the session file", async () => {
	const runtime = await harness([], true);
	try {
		assert.equal((await runtime.call('(store "resumed" {:n 7})')).isError, false);
		const path = runtime.manager.getSessionFile();
		assert.ok(path, "the fixture persists a session file");
		const reopened = SessionManager.open(path);
		const writes = reopened
			.getBranch()
			.filter((entry) => entry.type === "custom" && entry.customType === "codemode-store");
		assert.equal(writes.length, 1);
		assert.deepEqual(writes[0].type === "custom" ? writes[0].data : undefined, {
			set: { resumed: { n: 7 } },
			delete: [],
		});
		runtime.manager.setSessionFile(path);
		assert.match(output(await runtime.call('(load "resumed")')), /\{:n 7\}/);
	} finally {
		runtime.close();
	}
});

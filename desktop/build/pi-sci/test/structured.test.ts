import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { readJson, readJsonl } from "../src/structured-files.ts";
import { harness, output } from "./harness.ts";

async function data(runtime: Awaited<ReturnType<typeof harness>>, source: string) {
	const result = await runtime.call(`(text (json/generate ${source}))`);
	assert.equal(result.isError, false, output(result));
	const line = output(result)
		.split("\n")
		.find((line) => line.startsWith("{"));
	assert.ok(line, output(result));
	return JSON.parse(line);
}

test("complete JSON reads parse beyond the display limit without losing values", async () => {
	const runtime = await harness();
	try {
		writeFileSync(
			join(runtime.cwd, "large.json"),
			JSON.stringify({ padding: "x".repeat(60000), answer: 42 }),
		);
		const legacy = await runtime.call('(json/parse (await (tools/read {:path "large.json"})))');
		assert.equal(legacy.isError, true);
		const value = await data(
			runtime,
			'(let [r (await (fs/read-json "large.json"))] {:answer (:answer (:value r)) :length (count (:padding (:value r))) :truncated (:truncated r)})',
		);
		assert.deepEqual(value, { answer: 42, length: 60000, truncated: false });
		for (const [name, source, expected] of [
			["primitive", "false", false],
			["array", '[1,null,"Mixed_case"]', [1, null, "Mixed_case"]],
		] as const) {
			writeFileSync(join(runtime.cwd, `${name}.json`), source);
			assert.deepEqual(
				(await data(runtime, `(await (fs/read-json "${name}.json"))`)).value,
				expected,
			);
		}
		for (const [name, source, error] of [
			["malformed", "{", /Invalid JSON/],
			["large", JSON.stringify("x".repeat(1024 * 1024)), /JSON input exceeds/],
			["infinite", "1e400", /finite/],
		] as const) {
			writeFileSync(join(runtime.cwd, `${name}.json`), source);
			const result = await runtime.call(`(await (fs/read-json "${name}.json"))`);
			assert.equal(result.isError, true, output(result));
			assert.match(output(result), error);
		}
	} finally {
		runtime.close();
	}
});

test("JSONL pages include oversized session records and resume without omissions", async () => {
	const runtime = await harness();
	try {
		const rows = [
			{ type: "message", message: { role: "user", content: [{ text: "å".repeat(60000) }] } },
			{ type: "message", message: { role: "assistant", content: [{ text: "answer" }] } },
			{ type: "custom", value: null },
		];
		const firstLine = JSON.stringify(rows[0]);
		writeFileSync(
			join(runtime.cwd, "session.jsonl"),
			`${firstLine}\r\n\n${JSON.stringify(rows[1])}\n${JSON.stringify(rows[2])}`,
		);
		const fields = '{:type ["type"] :role ["message" "role"] :content ["message" "content"]}';
		const first = await data(
			runtime,
			`(await (fs/read-jsonl "session.jsonl" {:fields ${fields} :limit 1 :max_string_chars 4}))`,
		);
		assert.deepEqual(first.items, [
			{
				line: 1,
				value: { type: "mess", role: "user", content: [{ text: "åååå" }] },
				value_truncated: true,
			},
		]);
		assert.deepEqual(first.next_cursor, { offset: Buffer.byteLength(firstLine) + 3, line: 3 });
		assert.equal(first.truncated, true);
		const second = await data(
			runtime,
			`(await (fs/read-jsonl "session.jsonl" {:fields ${fields} :cursor {:offset ${first.next_cursor.offset} :line ${first.next_cursor.line}}}))`,
		);
		assert.deepEqual(second.items, [
			{
				line: 3,
				value: { type: "message", role: "assistant", content: [{ text: "answer" }] },
				value_truncated: false,
			},
			{ line: 4, value: { type: "custom" }, value_truncated: false },
		]);
		assert.equal(second.next_cursor, null);
		assert.equal(second.truncated, false);
		writeFileSync(join(runtime.cwd, "bad.jsonl"), "{}\nnot-json\n");
		const bad = await runtime.call('(await (fs/read-jsonl "bad.jsonl"))');
		assert.equal(bad.isError, true);
		assert.match(output(bad), /Invalid JSON.*line 2/);
		writeFileSync(join(runtime.cwd, "invalid-utf8.jsonl"), Buffer.from([0xff, 10]));
		const utf8 = await runtime.call('(await (fs/read-jsonl "invalid-utf8.jsonl"))');
		assert.equal(utf8.isError, true);
		assert.match(output(utf8), /Invalid JSON.*line 1/);
		const cursor = await runtime.call(
			'(await (fs/read-jsonl "session.jsonl" {:cursor {:offset 2 :line 1}}))',
		);
		assert.equal(cursor.isError, true);
		assert.match(output(cursor), /record boundary/);
		writeFileSync(join(runtime.cwd, "empty.jsonl"), "\n\r\n");
		assert.deepEqual((await data(runtime, '(await (fs/read-jsonl "empty.jsonl"))')).items, []);
	} finally {
		runtime.close();
	}
});

test("JSONL byte budgets resume at the deferred record and never silently drop an item", async () => {
	const runtime = await harness();
	try {
		writeFileSync(
			join(runtime.cwd, "budget.jsonl"),
			[1, 2, 3].map((id) => JSON.stringify({ id, text: "x".repeat(16384) })).join("\n"),
		);
		const first = await readJsonl(join(runtime.cwd, "budget.jsonl"), { max_string_chars: 16384 });
		assert.deepEqual(
			first.items.map((r) => (r.value as { id: number }).id),
			[1, 2],
		);
		assert.deepEqual(first.next_cursor?.line, 3);
		const second = await readJsonl(join(runtime.cwd, "budget.jsonl"), {
			max_string_chars: 16384,
			cursor: first.next_cursor ?? undefined,
		});
		assert.deepEqual(
			second.items.map((r) => (r.value as { id: number }).id),
			[3],
		);
		assert.equal(second.truncated, false);
		writeFileSync(
			join(runtime.cwd, "projection.jsonl"),
			JSON.stringify({ id: 7, texts: Array(30).fill("x".repeat(2000)) }),
		);
		await assert.rejects(
			readJsonl(join(runtime.cwd, "projection.jsonl"), {}),
			/projection at line 1 exceeds 48 KiB/,
		);
		assert.deepEqual(
			(await readJsonl(join(runtime.cwd, "projection.jsonl"), { fields: { id: ["id"] } })).items,
			[{ line: 1, value: { id: 7 }, value_truncated: false }],
		);
		writeFileSync(
			join(runtime.cwd, "oversized.jsonl"),
			JSON.stringify("x".repeat(2 * 1024 * 1024)),
		);
		await assert.rejects(
			readJsonl(join(runtime.cwd, "oversized.jsonl"), {}),
			/line 1 exceeds the 2 MiB limit/,
		);
		if (process.platform !== "win32") {
			const fifo = join(runtime.cwd, "fifo");
			assert.equal(spawnSync("mkfifo", [fifo]).status, 0);
			await assert.rejects(readJson(fifo), /regular file/);
			await assert.rejects(readJsonl(fifo, {}), /regular file/);
		}
		const signal = AbortSignal.abort(new Error("fixture cancelled"));
		await assert.rejects(readJson(join(runtime.cwd, "budget.jsonl"), signal), /fixture cancelled/);
		await assert.rejects(
			readJsonl(join(runtime.cwd, "budget.jsonl"), {}, signal),
			/fixture cancelled/,
		);
	} finally {
		runtime.close();
	}
});

test("the local SCI runner prints bounded results and propagates failure", () => {
	const runner = new URL("../scripts/run-sci.mjs", import.meta.url);
	const good = spawnSync(process.execPath, [runner.pathname, "(+ 20 22)"], {
		encoding: "utf8",
		timeout: 30000,
	});
	assert.equal(good.status, 0, good.stderr + good.stdout);
	assert.match(good.stdout, /Script completed[\s\S]*\n42\n/);
	const bad = spawnSync(
		process.execPath,
		[runner.pathname, '(throw (ex-info "fixture failure" {}))'],
		{ encoding: "utf8", timeout: 30000 },
	);
	assert.equal(bad.status, 1, bad.stderr + bad.stdout);
	assert.match(bad.stdout, /Script failed[\s\S]*fixture failure/);
	const write = spawnSync(
		process.execPath,
		[runner.pathname, '(await (tools/write {:path "blocked.txt" :content "blocked"}))'],
		{ encoding: "utf8", timeout: 30000 },
	);
	assert.equal(write.status, 1, write.stderr + write.stdout);
	assert.match(write.stdout, /permits only read-only fs tool operations/);
});

test("structured reads pass through validation, blocking, and redaction", async () => {
	const runtime = await harness([
		(pi) => {
			pi.on("tool_call", (event) => {
				if (event.toolName === "fs" && event.input.path === "blocked.json")
					return { block: true, reason: "structured fixture blocked" };
			});
			pi.on("tool_result", (event) => {
				if (event.toolName === "fs" && event.input.path === "secret.json")
					return {
						content: [{ type: "text", text: "redacted" }],
						structuredContent: { value: "redacted" },
					};
			});
		},
	]);
	try {
		writeFileSync(join(runtime.cwd, "secret.json"), '{"secret":"hidden"}');
		assert.deepEqual(await data(runtime, '(await (fs/read-json "secret.json"))'), {
			value: "redacted",
		});
		const blocked = await runtime.call('(await (fs/read-json "blocked.json"))');
		assert.equal(blocked.isError, true);
		assert.match(output(blocked), /structured fixture blocked/);
		const invalid = await runtime.call('(await (fs/read-jsonl "secret.json" {:limit 0}))');
		assert.equal(invalid.isError, true);
	} finally {
		runtime.close();
	}
});

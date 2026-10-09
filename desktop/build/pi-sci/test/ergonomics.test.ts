import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { runCommand } from "../src/command-process.ts";
import { searchDomain } from "../src/files.ts";
import { signatures } from "../src/operations.ts";
import { condenseFailure } from "../src/output.ts";
import { harness, output } from "./harness.ts";

async function data(runtime: Awaited<ReturnType<typeof harness>>, source: string) {
	const r = await runtime.call(`(text (json/generate ${source}))`);
	assert.equal(r.isError, false, output(r));
	return JSON.parse(
		output(r)
			.split("\n")
			.find((line) => line.startsWith("{")) ?? "null",
	);
}

test("the agent prompt steers file work to SCI functions instead of bash and read", async () => {
	let prompt = "";
	const runtime = await harness(
		[
			(pi) => {
				pi.on("before_agent_start", (event) => {
					prompt = event.systemPrompt;
				});
			},
		],
		false,
		{},
	);
	try {
		await runtime.call('(text "ok")');
		assert.match(prompt, /Use SCI fs and search functions for file operations/);
		assert.doesNotMatch(prompt, /Use bash for file operations like ls, rg, find/);
	} finally {
		runtime.close();
	}
});

test("command and bash results print a status line and raw output", async () => {
	const runtime = await harness();
	try {
		const bash = output(
			await runtime.call("(text (await (tools/bash {:command \"printf 'a\\nb'\"})))"),
		);
		assert.match(bash, /\[exit 0(, [\d.]+ s)?\]\na\nb/);
		assert.doesNotMatch(bash, /Hint/);
		const piped = output(
			await runtime.call('(text (await (tools/bash {:command "printf a | grep a"})))'),
		);
		assert.doesNotMatch(piped, /Hint/);
		const listing = output(
			await runtime.call(
				'(await (tools/bash {:command "cd . && ls"})) (await (tools/bash {:command "grep -r x .; true"}))',
			),
		);
		assert.match(
			listing,
			/Hint: tools\/bash ran ls; search\/text, search\/files, fs\/list, and fs\/read/,
		);
		assert.equal(listing.match(/Hint:/g)?.length, 1);
		writeFileSync(join(runtime.cwd, "Makefile"), "hello:\n\t@printf 'make-domain'\n");
		const make = output(await runtime.call('(text (await (make/run "hello")))'));
		assert.match(make, /\[exit 0, \d+ ms\]\nmake-domain/);
		const edn = output(await runtime.call('(text (pr-str (await (make/run "hello"))))'));
		assert.match(edn, /:stdout "make-domain"/);
	} finally {
		runtime.close();
	}
});

test("the output budget clips large blocks fairly and spills the complete text", async () => {
	const runtime = await harness();
	try {
		const result = await runtime.call(
			';; @options: {"max_output_tokens": 100}\n(text "first-small") (text (str "HEAD" (apply str (repeat 3000 "x")) "TAIL")) (text "last-small")',
		);
		assert.equal(result.isError, false, output(result));
		const text = output(result);
		assert.match(text, /first-small/);
		assert.match(text, /last-small/);
		assert.match(text, /HEAD/);
		assert.match(text, /TAIL/);
		const [, path, first, last] =
			/\[clipped \d+ of 3008 chars; full text: \(fs\/read "([^"]+)" \{:start_line (\d+) :end_line (\d+)\}\)/.exec(
				text,
			) ?? [];
		assert.ok(path, text);
		assert.equal((result.details as { fullOutputPath: string }).fullOutputPath, path);
		const lines = readFileSync(path, "utf8").split("\n");
		assert.equal(
			lines.slice(Number(first) - 1, Number(last)).join("\n"),
			`HEAD${"x".repeat(3000)}TAIL`,
		);
		const small = await runtime.call('(text "fits")');
		assert.doesNotMatch(output(small), /clipped/);
	} finally {
		runtime.close();
	}
});

test("failures list repeated calls once and omit interpreter stack frames", async () => {
	assert.equal(
		condenseFailure(
			"Error: boom\n    at e$ (codemode.js:2135:54)\n\nTool calls made before the failure (they are not undone): fs (ok), fs (ok), fs (ok), jj (error)",
		),
		"Error: boom\n\nTool calls made before the failure (they are not undone): fs (ok) x3, jj (error)",
	);
	const runtime = await harness();
	try {
		const result = await runtime.call(
			'(doseq [_ (range 3)] (await (fs/exists? "x"))) (throw (ex-info "boom" {}))',
		);
		assert.equal(result.isError, true);
		assert.match(output(result), /fs \(ok\) x3/);
		assert.doesNotMatch(output(result), /codemode\.js:\d+/);
	} finally {
		runtime.close();
	}
});

test("errors explain how to fix the program", async () => {
	const runtime = await harness();
	try {
		const cases: [string, RegExp][] = [
			[
				"(let [b (inc 1)\n  (* b 2)))",
				/1 \| \(let \[b \(inc 1\)\n\s+\^ unclosed form opens here[\s\S]*2 \| {3}\(\* b 2\)\)\)\n\s+\^ reader stopped here/,
			],
			['(await (tools.edit {:path "x"}))', /Hint: Use tools\/edit; Clojure namespaces use a slash/],
			["(await (git/status))", /Hint: There is no git namespace/],
			['(store "k" {:tags #{:a}})', /Value at \[:tags\] is a set, which is not JSON-compatible/],
			['(store "k" [(fs/exists? "x")])', /Value at \[0\] is a promise \(missing await\?\)/],
			[
				'(await (search/files {:pattern "*.clj"}))',
				/search\/files does not accept :pattern and requires :glob\. Accepted keys: .*:glob/,
			],
			['(await (search/text "(unclosed"))', /Hint: pass :literal true/],
			[
				'(await (fs/read-jsonl "x.jsonl" {:fields ["type"]}))',
				/Hint: fs\/read-jsonl takes :path string, :cursor \{:offset integer :line integer\}, :fields \{string \[string\]\}, :limit integer/,
			],
		];
		for (const [source, expected] of cases) {
			const result = await runtime.call(source);
			assert.equal(result.isError, true, source);
			assert.match(output(result), expected);
		}
		assert.equal((await runtime.call("(defsession helper [] 1)")).isError, false);
		const unqualified = await runtime.call("(text (helper))");
		assert.equal(unqualified.isError, true);
		assert.match(
			output(unqualified),
			/Hint: Use session\/helper; defsession definitions live in the session namespace/,
		);
		assert.doesNotMatch(output(await runtime.call("(text (missing))")), /Hint: Use session/);
	} finally {
		runtime.close();
	}
});

test("fs glob, disk usage, and sorted listings replace ls, find, and du", async () => {
	const runtime = await harness();
	try {
		const cwd = runtime.cwd;
		mkdirSync(join(cwd, "sub", "deep"), { recursive: true });
		writeFileSync(join(cwd, "a1.txt"), "1");
		writeFileSync(join(cwd, "a2.txt"), "22");
		writeFileSync(join(cwd, ".a3.txt"), "333");
		writeFileSync(join(cwd, "sub", "deep", "c.txt"), "4444");
		const glob = await data(runtime, '(await (fs/glob "a?.txt"))');
		assert.deepEqual(glob.items, [join(cwd, "a1.txt"), join(cwd, "a2.txt")]);
		assert.equal(glob.truncated, false);
		assert.deepEqual((await data(runtime, '(await (fs/glob "**/c.txt"))')).items, [
			join(cwd, "sub", "deep", "c.txt"),
		]);
		assert.deepEqual((await data(runtime, '(await (fs/glob ".a*"))')).items, [
			join(cwd, ".a3.txt"),
		]);
		assert.deepEqual(
			(await data(runtime, `(await (fs/glob ${JSON.stringify(join(cwd, "[ab]1.*"))}))`)).items,
			[join(cwd, "a1.txt")],
		);
		const usage = await data(runtime, '(await (fs/disk-usage "sub"))');
		assert.deepEqual(
			[usage.bytes >= 4, usage.files, usage.directories, usage.truncated],
			[true, 1, 2, false],
		);
		const sorted = await data(runtime, '(await (fs/list {:sort "size" :limit 2}))');
		assert.equal(sorted.items.length, 2);
		const files = (await data(runtime, '(await (fs/list {:sort "size" :stat true}))')).items.filter(
			(item: { name: string }) => /a\d\.txt$/.test(item.name),
		);
		assert.deepEqual(
			files.map((item: { name: string; size: number }) => [item.name, item.size]),
			[
				[".a3.txt", 3],
				["a2.txt", 2],
				["a1.txt", 1],
			],
		);
		assert.equal(sorted.truncated, true);
	} finally {
		runtime.close();
	}
});

test("sys reports host facts and redacts sensitive variables", async () => {
	process.env.PI_SCI_TEST_TOKEN = "secret";
	process.env.PI_SCI_TEST_PLAIN = "visible";
	const runtime = await harness();
	try {
		const env = await data(runtime, '(await (sys/env {:prefix "PI_SCI_TEST_"}))');
		assert.deepEqual(env.values, { PI_SCI_TEST_PLAIN: "visible", PI_SCI_TEST_TOKEN: "[redacted]" });
		assert.deepEqual((await data(runtime, '(await (sys/env "PI_SCI_TEST_ABSENT"))')).values, {
			PI_SCI_TEST_ABSENT: null,
		});
		const which = await data(runtime, '(await (sys/which ["sh" "pi-sci-missing-program"]))');
		assert.match(which.found.sh, /\/sh$/);
		assert.equal(which.found["pi-sci-missing-program"], null);
		assert.ok((await data(runtime, "(await (sys/disk))")).total_bytes > 0);
		assert.equal((await data(runtime, "(await (sys/info))")).platform, process.platform);
		if (process.platform === "linux") {
			assert.ok((await data(runtime, "(await (sys/memory))")).total_bytes > 0);
			const probe = spawn("sh", ["-c", "sleep 30; true", "pi-sci-process-probe"]);
			try {
				const found = await data(runtime, '(await (sys/processes "pi-sci-process-probe"))');
				assert.deepEqual(
					found.items.map((item: { pid: number }) => item.pid),
					[probe.pid],
				);
			} finally {
				probe.kill();
			}
		}
	} finally {
		delete process.env.PI_SCI_TEST_TOKEN;
		delete process.env.PI_SCI_TEST_PLAIN;
		runtime.close();
	}
});

test("jj patch and Guix lint, style, and download build separate arguments", async () => {
	const runtime = await harness();
	try {
		assert.equal((await runCommand("jj", ["git", "init"], { cwd: runtime.cwd })).exit_code, 0);
		writeFileSync(join(runtime.cwd, "file.txt"), "hello\n");
		const patch = await data(runtime, "(await (jj/patch))");
		assert.match(patch.stdout, /^diff --git a\/file\.txt b\/file\.txt/m);
		assert.match((await data(runtime, "(await (jj/patch {:stat true}))")).stdout, /file\.txt/);
		const argvFile = join(runtime.cwd, "argv.json");
		const fixture = fileURLToPath(new URL("./guix-fixture.ts", import.meta.url));
		writeFileSync(
			join(runtime.cwd, "guix"),
			`#!${process.execPath}\nimport ${JSON.stringify(fixture)};\n`,
			{
				mode: 0o700,
			},
		);
		const env = `(json/parse ${JSON.stringify(JSON.stringify({ PATH: `${runtime.cwd}:${process.env.PATH}`, GUIX_ARGV_FILE: argvFile }))})`;
		const argv = () => JSON.parse(readFileSync(argvFile, "utf8"));
		await data(
			runtime,
			`(await (guix/lint "hello" {:load_paths ["modules"] :checkers ["description" "inputs-should-be-native"] :env ${env}}))`,
		);
		assert.deepEqual(argv(), [
			"lint",
			"-L",
			"modules",
			"--checkers=description,inputs-should-be-native",
			"--",
			"hello",
		]);
		await data(runtime, `(await (guix/style {:files ["a.scm"] :dry_run true :env ${env}}))`);
		assert.deepEqual(argv(), ["style", "--dry-run", "--whole-file", "--", "a.scm"]);
		assert.equal((await runtime.call(`(await (guix/style {:env ${env}}))`)).isError, true);
		const download = await data(
			runtime,
			`(await (guix/download "https://example.org/x.tar.gz" {:env ${env}}))`,
		);
		assert.deepEqual(argv(), ["download", "--", "https://example.org/x.tar.gz"]);
		assert.equal(download.store_path, "/gnu/store/fixture-output");
	} finally {
		runtime.close();
	}
});

test("generated signatures list positional arguments and option keys", () => {
	assert.deepEqual(signatures(searchDomain), [
		"(search/text query {:path :paths :glob :literal :ignore_case :limit})",
		"(search/files glob {:path :paths :limit})",
	]);
});

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { runCommand } from "../src/command-process.ts";
import { searchDomain } from "../src/files.ts";
import { signatures } from "../src/operations.ts";
import { condenseFailure, throttleUpdates } from "../src/output.ts";
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
			[
				'(text "a") (await tools.edit {:path "x"})',
				/await takes one expression[\s\S]*codemode\.clj:1:12/,
			],
			['(await fs/read "x")', /await takes one expression; write \(await \(f args\)\)/],
			["(text tools.edit)", /Hint: Use tools\/edit; Clojure namespaces use a slash/],
			['(Long/parseLong "1")', /Hint: Use parse-long\./],
			['(Double/parseDouble "1")', /Hint: Use parse-double\./],
			['(spit "x" "y")', /Hint: Use \(await \(tools\/write/],
			["(StringBuilder.)", /Hint: Use str or clojure\.string\/join\./],
			["(try 1 (catch Exception e 2))", /Hint: Use \(catch :default e \.\.\.\)/],
			['(js/JSON.parse "1")', /Hint: Use json\/parse/],
			[
				'(mapv (fn [p] (await (fs/read p))) ["a"])',
				/Hint: await works in the script body and in functions marked \^:async/,
			],
			["(await (git/status))", /Hint: There is no git namespace/],
			['(store "k" {:tags #{:a}})', /Value at \[:tags\] is a set, which is not JSON-compatible/],
			['(store "k" [(fs/exists? "x")])', /Value at \[0\] is a promise \(missing await\?\)/],
			[
				'(await (search/files {:pattern "*.clj" :depth 2}))',
				/search\/files does not accept :depth\. Accepted keys: .*:glob/,
			],
			[
				'(await (search/files {:pattern "*.clj" :glob "*.clj"}))',
				/search\/files takes :glob; :pattern is an alias for it\. Pass only one\./,
			],
			['(await (search/text "(unclosed"))', /Hint: pass :literal true/],
			["(await (fs/list 42))", /fs\/list takes a path or an options map\. Accepted keys: .*:depth/],
			[
				'(await (fs/read "x" 2))',
				/fs\/read takes a path or an options map\. Accepted keys: .*:start_line/,
			],
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
		const unawaited = await runtime.call('(let [r (fs/exists? "x")] (pr-str r))');
		assert.equal(unawaited.isError, false);
		assert.match(output(unawaited), /Hint: 1 call was still running when the script ended/);
		assert.doesNotMatch(
			output(await runtime.call('(pr-str (await (fs/exists? "x")))')),
			/still running/,
		);
	} finally {
		runtime.close();
	}
});

test("number parsing, formatting, and Math functions are available", async () => {
	const runtime = await harness();
	try {
		assert.deepEqual(
			await data(
				runtime,
				'{:long (parse-long "-12") :double (parse-double "1.5") :bad (parse-long "x") :abs (abs -3) :format (format "%.2f|%s|%03d" 2.5 "a" 7) :ceil (Math/ceil 1.2) :pow (Math/pow 2 10)}',
			),
			{ long: -12, double: 1.5, bad: null, abs: 3, format: "2.50|a|007", ceil: 2, pow: 1024 },
		);
	} finally {
		runtime.close();
	}
});

test("progress updates are throttled to the latest value and dropped after stop", async () => {
	const seen: number[] = [];
	const progress = throttleUpdates((value: number) => seen.push(value), 40);
	for (let i = 1; i <= 1000; i++) progress.update?.(i);
	assert.deepEqual(seen, [1]);
	await new Promise((resolve) => setTimeout(resolve, 60));
	assert.deepEqual(seen, [1, 1000]);
	progress.update?.(1001);
	progress.update?.(1002);
	progress.stop();
	await new Promise((resolve) => setTimeout(resolve, 60));
	assert.deepEqual(seen, [1, 1000]);
	assert.equal(throttleUpdates(undefined).update, undefined);
});

test("results name paths relative to the call directory and flag partial reads", async () => {
	const runtime = await harness();
	try {
		const cwd = runtime.cwd;
		mkdirSync(join(cwd, "sub"));
		writeFileSync(join(cwd, "sub", "a.txt"), "x\n");
		writeFileSync(join(cwd, "paths.json"), JSON.stringify({ path: join(cwd, "sub", "a.txt") }));
		const listing = await data(runtime, '(await (fs/list "sub"))');
		assert.equal(listing.root, "sub");
		assert.deepEqual(
			listing.items.map((item: { path: string }) => item.path),
			["sub/a.txt"],
		);
		const scoped = await data(runtime, '(await (fs/list "." {:cwd "sub"}))');
		assert.equal(scoped.root, ".");
		assert.deepEqual(
			scoped.items.map((item: { path: string }) => item.path),
			["a.txt"],
		);
		assert.equal((await data(runtime, '(await (fs/stat "/"))')).path, "/");
		const json = await data(runtime, '(await (fs/read-json "paths.json"))');
		assert.equal(json.path, "paths.json");
		assert.equal(json.value.path, join(cwd, "sub", "a.txt"));

		writeFileSync(
			join(cwd, "big.jsonl"),
			Array.from({ length: 2000 }, (_, i) => JSON.stringify({ i, pad: "x".repeat(100) })).join(
				"\n",
			),
		);
		const cut = output(
			await runtime.call('(count (:items (await (fs/read-jsonl "big.jsonl" {:limit 1000}))))'),
		);
		assert.match(
			cut,
			/Hint: fs\/read-jsonl returned part of big\.jsonl \(\d+ of 1000 requested records\)\. Pass :cursor \{:offset \d+, :line \d+\} to read the rest\./,
		);
		const paged = output(
			await runtime.call(
				'(loop [c nil n 0] (let [r (await (fs/read-jsonl "big.jsonl" (cond-> {:limit 1000} c (assoc :cursor c)))) n (+ n (count (:items r)))] (if (:next_cursor r) (recur (:next_cursor r) n) n)))',
			),
		);
		assert.match(paged, /2000/);
		assert.doesNotMatch(paged, /Hint/);
		writeFileSync(
			join(cwd, "long.txt"),
			Array.from({ length: 3000 }, (_, i) => `line ${i}`).join("\n"),
		);
		assert.match(
			output(await runtime.call('(count (:text (await (fs/read "long.txt"))))')),
			/Hint: fs\/read returned part of long\.txt \(lines 1-2000 of 3000\)\. Pass :start_line 2001 to read the rest\./,
		);
		assert.doesNotMatch(
			output(await runtime.call('(count (:text (await (fs/read "long.txt" {:limit 5}))))')),
			/Hint/,
		);
	} finally {
		runtime.close();
	}
});

test("fs glob, disk usage, and sorted listings replace ls, find, and du", async () => {
	const runtime = await harness();
	try {
		const cwd = runtime.cwd;
		mkdirSync(join(cwd, "sub", "deep"), { recursive: true });
		mkdirSync(join(cwd, ".cache"));
		writeFileSync(join(cwd, ".cache", "c.txt"), "5");
		writeFileSync(join(cwd, "a1.txt"), "1");
		writeFileSync(join(cwd, "a2.txt"), "22");
		writeFileSync(join(cwd, ".a3.txt"), "333");
		writeFileSync(join(cwd, "sub", "deep", "c.txt"), "4444");
		const glob = await data(runtime, '(await (fs/glob "a?.txt"))');
		assert.deepEqual(glob.items, ["a1.txt", "a2.txt"]);
		assert.equal(glob.truncated, false);
		assert.deepEqual((await data(runtime, '(await (fs/glob {:glob "a?.txt"}))')).items, glob.items);
		const visible = await data(runtime, '(await (fs/glob "**/c.txt"))');
		assert.deepEqual(visible.items, ["sub/deep/c.txt"]);
		assert.ok(visible.skipped_hidden >= 1);
		const all = await data(runtime, '(await (fs/glob "**/c.txt" {:hidden true}))');
		assert.deepEqual(all.items, [".cache/c.txt", "sub/deep/c.txt"]);
		assert.equal(all.skipped_hidden, 0);
		assert.ok((await data(runtime, '(await (fs/glob "*.txt"))')).skipped_hidden >= 1);
		assert.deepEqual((await data(runtime, '(await (fs/glob ".a*"))')).items, [".a3.txt"]);
		assert.deepEqual(
			(await data(runtime, `(await (fs/glob ${JSON.stringify(join(cwd, "[ab]1.*"))}))`)).items,
			["a1.txt"],
		);
		const usage = await data(runtime, '(await (fs/disk-usage "sub"))');
		assert.deepEqual(
			[usage.bytes >= 4, usage.files, usage.directories, usage.truncated],
			[true, 1, 2, false],
		);
		assert.deepEqual(
			(await data(runtime, '(await (fs/list "sub"))')).items.map(
				(item: { name: string }) => item.name,
			),
			["deep"],
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

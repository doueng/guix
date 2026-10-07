import assert from "node:assert/strict";
import { mkdirSync, readFileSync, symlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { guixDomain } from "../src/builds.ts";
import { runCommand } from "../src/command-process.ts";
import { DOMAINS } from "../src/commands.ts";
import { collection } from "../src/operations.ts";
import { harness, output } from "./harness.ts";
import { PNG } from "./provider.ts";

async function data(runtime: Awaited<ReturnType<typeof harness>>, source: string) {
	const r = await runtime.call(`(text (json/generate ${source}))`);
	assert.equal(r.isError, false, output(r));
	const line = output(r)
		.split("\n")
		.find((line) => line.startsWith("{"));
	assert.ok(line, output(r));
	return JSON.parse(line);
}
const root = resolve(fileURLToPath(new URL("../../../..", import.meta.url)));
test("Jujutsu reads return typed JSON and mutations expose their operation to hooks", async () => {
	const calls: Record<string, unknown>[] = [];
	const runtime = await harness([
		(pi) => {
			pi.on("tool_call", (event) => {
				if (event.toolName === "jj") calls.push(event.input);
			});
		},
	]);
	try {
		assert.equal((await runCommand("jj", ["git", "init"], { cwd: runtime.cwd })).exit_code, 0);
		const filename = 'colon:31: quoted" file.clj';
		writeFileSync(join(runtime.cwd, filename), "hello\n");
		writeFileSync(join(runtime.cwd, ".gitignore"), "auth.json\nmodels-store.json\n.gitignore\n");
		const described = await data(runtime, '(await (jj/describe "line one\\nquoted \\"line\\""))');
		assert.equal(described.ok, true);
		assert.equal(calls.at(-1)?.operation, "describe");
		assert.equal(calls.at(-1)?.message, 'line one\nquoted "line"');
		const log = await data(runtime, '(await (jj/log {:revisions "@" :limit 10}))');
		assert.equal(log.items.length, 1);
		assert.equal(log.items[0].description, 'line one\nquoted "line"\n');
		assert.match(log.items[0]["commit-id"], /^[a-f0-9]+$/);
		assert.equal(log.items[0]["empty?"], false);
		assert.equal(log.truncated, false);
		assert.deepEqual((await data(runtime, "(await (jj/bookmark-list))")).items, []);
		const ops = await data(runtime, "(await (jj/op-log {:limit 1}))");
		assert.equal(ops.items.length, 1);
		assert.equal(typeof ops.items[0].id, "string");
		const diff = await data(runtime, "(await (jj/diff))");
		assert.deepEqual(
			diff.items.map((x: { path: string; status: string }) => ({ path: x.path, status: x.status })),
			[{ path: filename, status: "added" }],
		);
		const files = await data(runtime, "(await (jj/files-changed))");
		assert.deepEqual(files.items, [filename]);
		const read = await data(runtime, `(await (jj/file-show {:path ${JSON.stringify(filename)}}))`);
		assert.equal(read.text, "hello\n");
		const status = await data(runtime, "(await (jj/status))");
		assert.equal(status.working_copy["commit-id"], log.items[0]["commit-id"]);
		assert.equal((await runtime.call('(await (jj/new {:message "next"}))')).isError, false);
		const bounded = await data(runtime, '(await (jj/log {:revisions "all()" :limit 1}))');
		assert.equal(bounded.items.length, 1);
		assert.equal(bounded.truncated, true);
		assert.equal(
			(await runtime.call('(await (jj/diff {:from "@-" :revisions "@"}))')).isError,
			true,
		);
		const before = calls.length;
		assert.equal((await runtime.call('(await (jj/log {:args ["status"]}))')).isError, true);
		assert.equal(calls.length, before);
	} finally {
		runtime.close();
	}
});

test("Pi search returns records without parsing display text and preserves limits and ignore rules", async () => {
	const runtime = await harness();
	try {
		writeFileSync(join(runtime.cwd, 'odd:23: "file.clj'), "needle needle\nsecond needle\n");
		writeFileSync(join(runtime.cwd, ".gitignore"), "ignored.clj\n");
		writeFileSync(join(runtime.cwd, "ignored.clj"), "needle\n");
		assert.equal((await runCommand("jj", ["git", "init"], { cwd: runtime.cwd })).exit_code, 0);
		const found = await data(runtime, '(await (search/text "needle"))');
		assert.equal(found.items.length, 2, JSON.stringify(found));
		assert.equal(found.truncated, false);
		assert.equal(found.items[0].path, join(runtime.cwd, 'odd:23: "file.clj'));
		assert.equal(found.items[0].line, 1);
		assert.equal(found.items[0].column, 1);
		assert.deepEqual(found.items[0].columns, [1, 8]);
		assert.equal(
			(await data(runtime, '(await (search/text "needle" {:limit 1}))')).truncated,
			true,
		);
		const no = await data(runtime, '(await (search/text "absent-needle"))');
		assert.deepEqual(no.items, []);
		assert.equal(no.truncated, false);
		assert.match(
			output(await runtime.call('(await (search/text "needle" {:timeout_ms 1}))')),
			/deadline exceeded/,
		);
		mkdirSync(join(runtime.cwd, "one"));
		mkdirSync(join(runtime.cwd, "two"));
		writeFileSync(join(runtime.cwd, "one", "a.clj"), "shared\n");
		writeFileSync(join(runtime.cwd, "two", "b.clj"), "shared\n");
		const multi = await data(
			runtime,
			'(await (search/text {:query "shared" :paths ["one" "two"]}))',
		);
		assert.equal(multi.items.length, 2);
		assert.equal(multi.truncated, false);
		assert.equal(
			(await data(runtime, '(await (search/text {:query "shared" :paths ["one" "two"] :limit 1}))'))
				.truncated,
			true,
		);
		const multiFiles = await data(
			runtime,
			'(await (search/files {:glob "*.clj" :paths ["one" "two"]}))',
		);
		assert.deepEqual(
			new Set(multiFiles.items),
			new Set([join(runtime.cwd, "one", "a.clj"), join(runtime.cwd, "two", "b.clj")]),
		);
		assert.equal(
			(await runtime.call('(await (search/text "needle" {:path "." :paths ["one"]}))')).isError,
			true,
		);
		const files = await data(runtime, '(await (search/files {:glob "*.clj"}))');
		assert.ok(files.items.includes('odd:23: "file.clj'));
		assert.equal((await runtime.call('(await (rg/search "needle"))')).isError, true);
		assert.equal((await runtime.call("(await (git/status))")).isError, true);
		assert.deepEqual(
			DOMAINS.map((d) => d.id),
			["jj", "guix", "make", "search", "fs", "repo"],
		);
	} finally {
		runtime.close();
	}
});

test("Pi-backed file reads have clean data and continuation and filesystem listings do not follow links", async () => {
	const runtime = await harness();
	try {
		writeFileSync(join(runtime.cwd, "file.txt"), "a\nb\nc\n");
		const first = await data(runtime, '(await (fs/read "file.txt" {:start_line 2 :end_line 2}))');
		assert.equal(first.text, "b");
		assert.equal(first.start_line, 2);
		assert.equal(first.end_line, 2);
		assert.equal(first.total_lines, 4);
		assert.equal(first.next_line, 3);
		assert.equal(first.truncated, true);
		writeFileSync(join(runtime.cwd, "image.png"), Buffer.from(PNG, "base64"));
		const image = await runtime.call(
			'(let [r (await (fs/read "image.png"))] (text (:kind r)) (if (= "image" (:kind r)) (image (:image r)) (text (:note r))))',
		);
		assert.equal(image.isError, false, output(image));
		assert.ok(
			image.content.some((block) => block.type === "image") ||
				/image-omitted\n.*Read image file/s.test(output(image)),
		);
		const tail = await data(runtime, '(await (fs/read {:path "file.txt" :start_line 3}))');
		assert.equal(tail.text, "c\n");
		assert.equal(tail.next_line, null);
		assert.equal(tail.truncated, false);
		writeFileSync(join(runtime.cwd, "big.txt"), "x".repeat(60000));
		const big = await data(runtime, '(await (fs/read "big.txt"))');
		assert.equal(big.truncated, true);
		assert.equal(big.next_line, null);
		assert.equal((await data(runtime, '(await (fs/stat "file.txt"))')).kind, "file");
		assert.match(output(await runtime.call('(text (await (fs/exists? "missing")))')), /false/);
		assert.match(output(await runtime.call('(text (await (fs/exists? "file.txt")))')), /true/);
		mkdirSync(join(runtime.cwd, "nested"));
		writeFileSync(join(runtime.cwd, "nested", "child"), "ok");
		symlinkSync(runtime.cwd, join(runtime.cwd, "loop"));
		const listed = await data(runtime, "(await (fs/list {:depth 2 :limit 100}))");
		assert.equal(listed.truncated, false);
		assert.equal(listed.items.filter((x: { kind: string }) => x.kind === "symlink").length, 1);
		assert.ok(listed.items.some((x: { name: string }) => x.name === "child"));
		const bounded = await data(runtime, "(await (fs/list {:limit 1}))");
		assert.equal(bounded.items.length, 1);
		assert.equal(bounded.truncated, true);
		assert.equal(
			(await runtime.call('(await (fs/read "file.txt" {:start_line 3 :end_line 1}))')).isError,
			true,
		);
	} finally {
		runtime.close();
	}
});

test("semantic capabilities obey blocking, redaction, schemas, and persistent helper replay", async () => {
	let calls = 0;
	const runtime = await harness([
		(pi) => {
			pi.on("tool_call", (event) => {
				if (event.toolName === "search") {
					calls++;
					if (event.input.query === "blocked") return { block: true, reason: "SEARCH BLOCKED" };
				}
			});
			pi.on("tool_result", (event) => {
				if (event.toolName === "fs" && event.input.operation === "stat")
					return { content: [{ type: "text", text: "REDACTED" }] };
			});
		},
	]);
	try {
		writeFileSync(join(runtime.cwd, "helper.clj"), "needle");
		assert.match(output(await runtime.call('(await (search/text "blocked"))')), /SEARCH BLOCKED/);
		assert.match(output(await runtime.call('(await (fs/stat "helper.clj"))')), /REDACTED/);
		const before = calls;
		assert.equal(
			(await runtime.call('(await (search/text {:query "needle" :limit -1}))')).isError,
			true,
		);
		assert.equal(calls, before);
		assert.equal(
			(
				await runtime.call(
					'(defsession ^:async find-needle [] (:items (await (search/text "needle" {:glob "*.clj"}))))',
				)
			).isError,
			false,
		);
		assert.match(output(await runtime.call("(await (session/find-needle))")), /helper.clj/);
	} finally {
		runtime.close();
	}
});

test("Make and repository actions normalize targets and reject use outside this checkout", async () => {
	const runtime = await harness();
	try {
		writeFileSync(
			join(runtime.cwd, "Makefile"),
			'hello:\n\t@printf "hello semantic"\nother:\n\t@true\n',
		);
		const build = await data(runtime, '(await (make/run "hello"))');
		assert.equal(build.ok, true);
		assert.equal(build.stdout, "hello semantic");
		const targets = await data(runtime, "(await (make/targets))");
		assert.deepEqual(targets.items, ["hello", "other"]);
		assert.equal((await runtime.call("(await (repo/pi-sci-test {:dry_run true}))")).isError, true);
		const r = await data(
			runtime,
			`(await (repo/pi-sci-test {:dry_run true :cwd ${JSON.stringify(root)}}))`,
		);
		assert.equal(r.ok, true);
		assert.equal(r.target, "pi-sci-test");
		assert.match(r.stdout, /verify.sh/);
	} finally {
		runtime.close();
	}
});

test("Guix semantic options generate separate arguments and normalize only successful store outputs", async () => {
	const runtime = await harness();
	try {
		const argvFile = join(runtime.cwd, "argv.json");
		const fixture = fileURLToPath(new URL("./guix-fixture.ts", import.meta.url));
		writeFileSync(
			join(runtime.cwd, "guix"),
			`#!${process.execPath}\nimport ${JSON.stringify(fixture)};\n`,
			{ mode: 0o700 },
		);
		const env = JSON.stringify({
			PATH: `${runtime.cwd}:${process.env.PATH}`,
			GUIX_ARGV_FILE: argvFile,
		});
		const envForm = `(json/parse ${JSON.stringify(env)})`;
		const built = await data(
			runtime,
			`(await (guix/build {:file "a file.scm" :load_paths ["modules"] :dry_run true :env ${envForm}}))`,
		);
		assert.equal(built.ok, true);
		assert.deepEqual(built.outputs, ["/gnu/store/fixture-output"]);
		assert.equal(built.outputs_complete, true);
		assert.deepEqual(JSON.parse(readFileSync(argvFile, "utf8")), [
			"build",
			"--file",
			"a file.scm",
			"-L",
			"modules",
			"--dry-run",
			"--",
		]);
		assert.equal(
			(await data(runtime, `(await (guix/system-build {:config "system.scm" :env ${envForm}}))`))
				.ok,
			true,
		);
		assert.deepEqual(JSON.parse(readFileSync(argvFile, "utf8")), [
			"system",
			"build",
			"--",
			"system.scm",
		]);
		await data(
			runtime,
			`(await (guix/shell {:packages ["hello"] :command ["printf" "a b"] :env ${envForm}}))`,
		);
		assert.deepEqual(JSON.parse(readFileSync(argvFile, "utf8")), [
			"shell",
			"hello",
			"--",
			"printf",
			"a b",
		]);
		const failureEnv = `(assoc ${envForm} :GUIX_FAIL "1")`;
		const failed = await data(
			runtime,
			`(await (guix/build {:packages ["hello"] :env ${failureEnv}}))`,
		);
		assert.equal(failed.ok, false);
		assert.equal(failed.exit_code, 7);
		assert.deepEqual(failed.outputs, []);
		assert.equal(failed.outputs_complete, false);
		assert.equal(
			(await runtime.call('(await (guix/build {:file "f" :packages ["hello"]}))')).isError,
			true,
		);
	} finally {
		runtime.close();
	}
});

test("structured collection byte limits never claim completeness", () => {
	const r = collection(["x".repeat(30000), "y".repeat(30000)]);
	assert.equal(r.items.length, 1);
	assert.equal(r.truncated, true);
	assert.throws(() => collection(["x".repeat(60000)]), /record exceeds/);
	assert.equal(guixDomain.operations["system-build"].mutation, false);
});

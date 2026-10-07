import assert from "node:assert/strict";
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { CodemodeSandbox } from "@earendil-works/pi-codemode";
import { runCommand } from "../src/command-process.ts";
import { readEnvironment } from "../src/environment.ts";
import { compileSciSource } from "../src/source.ts";
import { harness, output } from "./harness.ts";

const fixture = fileURLToPath(new URL("./command-fixture.ts", import.meta.url));

function node(mode: string, args: string[] = [], signal?: AbortSignal, timeout_ms?: number) {
	return runCommand(process.execPath, [fixture, mode, ...args], {
		cwd: dirname(fixture),
		signal,
		timeout_ms,
	});
}

test("private argv execution keeps streams, nonzero status, arguments, cwd and environment", async () => {
	const streams = await node("streams");
	assert.equal(streams.stdout, "out");
	assert.equal(streams.stderr, "err");
	assert.equal(streams.exit_code, 7);
	assert.equal(streams.full_output_path, null);
	const args = ["space value", "$(touch SHOULD_NOT_EXIST)", "; echo nope", "'quoted'"];
	const result = await runCommand(process.execPath, [fixture, "args", ...args], {
		cwd: dirname(fixture),
		env: { SCI_TEST_VALUE: "kept" },
	});
	assert.deepEqual(JSON.parse(result.stdout), { args, cwd: dirname(fixture), env: "kept" });
	assert.equal(existsSync(join(dirname(fixture), "SHOULD_NOT_EXIST")), false);
});

test("large output stays bounded and points to a private complete log", async () => {
	const result = await node("burst");
	assert.equal(result.exit_code, 0);
	assert.equal(result.truncated, true);
	assert.ok(result.stdout.length < 66000 && result.stderr.length < 66000);
	assert.ok(result.full_output_path);
	try {
		const full = readFileSync(result.full_output_path, "utf8");
		assert.match(full, /a{10000}/);
		assert.match(full, /b{10000}/);
	} finally {
		rmSync(dirname(result.full_output_path), { recursive: true, force: true });
	}
});

test("oversized log output fails instead of exhausting host storage", async () => {
	await assert.rejects(node("loud"), /32 MiB log limit/);
});

test("deadline escalates past ignored SIGTERM and cleans up descendants", async () => {
	const result = await node("tree", [], undefined, 500);
	assert.equal(result.timed_out, true);
	assert.equal(result.exit_code, null);
	const pid = Number(/child=(\d+)/.exec(result.stdout)?.[1]);
	assert.ok(pid > 0);
	if (existsSync(`/proc/${pid}/stat`))
		assert.match(readFileSync(`/proc/${pid}/stat`, "utf8"), /\) Z /);
	else assert.throws(() => process.kill(pid, 0));
});

test("abort and unavailable executables reject without leaving commands running", async () => {
	const controller = new AbortController();
	const pending = node("wait", [], controller.signal);
	setTimeout(() => controller.abort(), 100);
	await assert.rejects(pending, /aborted/);
	await assert.rejects(
		runCommand("/nonexistent/pi-sci-command", [], { cwd: dirname(fixture) }),
		/ENOENT/,
	);
	const already = new AbortController();
	already.abort();
	await assert.rejects(node("wait", [], already.signal));
});

test("SCI domain functions execute real jj, Guix, and Make without a callable bash tool", async () => {
	const runtime = await harness([
		(pi) => {
			pi.registerCommand("disable-bash", {
				description: "Disable fixture bash",
				handler: async () => {
					pi.setActiveTools(pi.getActiveTools().filter((name) => name !== "bash"));
				},
			});
		},
	]);
	try {
		assert.equal((await runCommand("jj", ["git", "init"], { cwd: runtime.cwd })).exit_code, 0);
		writeFileSync(join(runtime.cwd, "Makefile"), "hello:\n\t@printf 'make-domain'\n");
		await runtime.session.prompt("/disable-bash");
		const result = await runtime.call(
			'(let [rs (await (all [(jj/status) (guix/version) (make/run {:args ["hello"]})]))] (doseq [r rs] (text (result/check r))))',
		);
		assert.equal(result.isError, false, output(result));
		assert.match(output(result), /:working_copy/);
		assert.match(output(result), /GNU Guix/);
		assert.match(output(result), /make-domain/);
		assert.equal(
			(await runtime.call('(await (tools/bash {:command "echo forbidden"}))')).isError,
			true,
		);
		assert.equal((await runtime.call("(await (git/status))")).isError, true);
		assert.equal((await runtime.call('(await (process/run {:argv ["jj"]}))')).isError, true);
		assert.equal((await runtime.call('(await (shell/run {:command "jj status"}))')).isError, true);
	} finally {
		runtime.close();
	}
});

test("registered domain calls obey hooks, validation and result redaction", async () => {
	let calls = 0;
	const runtime = await harness([
		(pi) => {
			pi.on("tool_call", (event) => {
				if (event.toolName !== "jj") return;
				calls++;
				if (event.input.operation === "status")
					return { block: true, reason: "fixture policy blocked jj" };
			});
			pi.on("tool_result", (event) => {
				if (event.toolName === "jj") return { content: [{ type: "text", text: "REDACTED" }] };
			});
		},
	]);
	try {
		assert.match(output(await runtime.call("(await (jj/status))")), /fixture policy blocked jj/);
		const safe = await runtime.call("(text (await (jj/version)))");
		assert.equal(safe.isError, false, output(safe));
		assert.match(output(safe), /REDACTED/);
		assert.doesNotMatch(output(safe), /jj 0\./);
		const before = calls;
		assert.equal((await runtime.call('(await (jj/run {:program "git"}))')).isError, true);
		assert.equal((await runtime.call('(await (jj/run {:args ["bad\\u0000arg"]}))')).isError, true);
		assert.equal(calls, before);
	} finally {
		runtime.close();
	}
});

test("persistent helpers replay against only the capabilities provided to the new sandbox", async () => {
	const runtime = await harness();
	try {
		const first = await runtime.call(
			"(defsession ^:async jj-version [] (result/stdout! (await (jj/version))))",
		);
		assert.equal(first.isError, false, output(first));
		const second = await runtime.call("(text (await (session/jj-version)))");
		assert.equal(second.isError, false, output(second));
		assert.match(output(second), /jj 0\./);
		const sandbox = new CodemodeSandbox();
		try {
			const missing = await sandbox.execute(
				compileSciSource(
					"(text (await (session/jj-version)))",
					[],
					readEnvironment(runtime.manager.getBranch()),
				),
			);
			assert.equal(missing.ok, false);
			assert.deepEqual(missing.calls, []);
			assert.deepEqual(missing.output, []);
		} finally {
			await sandbox.close();
		}
	} finally {
		runtime.close();
	}
});

test("the real codemode abort signal terminates a domain command and discards staged state", async () => {
	const runtime = await harness();
	try {
		const ready = join(runtime.cwd, "ready");
		writeFileSync(
			join(runtime.cwd, "Makefile"),
			`wait:\n\t@touch ready\n\t@${JSON.stringify(process.execPath)} ${JSON.stringify(fixture)} wait\n`,
		);
		const pending = runtime.call(
			'(defsession pending-value 1) (store "pending-value" 1) (await (make/run {:args ["wait"]}))',
		);
		for (let attempt = 0; !existsSync(ready) && attempt < 200; attempt++) {
			await new Promise((resolve) => setTimeout(resolve, 10));
		}
		assert.ok(existsSync(ready), "the domain command started");
		await runtime.session.abort();
		await pending;
		assert.match(
			output(await runtime.call('(text (load "pending-value")) (text (session/definitions))')),
			/nil/,
		);
		assert.deepEqual(readEnvironment(runtime.manager.getBranch()).operations, []);
	} finally {
		runtime.close();
	}
});

test("checked results throw for nonzero exits, deadlines, and signals", async () => {
	const runtime = await harness();
	try {
		assert.match(output(await runtime.call("(text (result/ok? {:exit_code 0}))")), /true/);
		for (const result of [
			"{:exit_code 1}",
			"{:exit_code 0 :timed_out true}",
			'{:exit_code nil :signal "SIGTERM"}',
		]) {
			const failed = await runtime.call(`(result/check ${result})`);
			assert.equal(failed.isError, true);
			assert.match(output(failed), /Host command failed/);
			const stderr = await runtime.call('(result/check {:exit_code 1 :stderr "WHY IT FAILED"})');
			assert.match(output(stderr), /WHY IT FAILED/);
		}
	} finally {
		runtime.close();
	}
});

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

for (const [name, source, legacyLanguage, rejected] of [
	[
		"Clojure",
		'(text (+ 20 22)) (text (:output (await (tools/bash {:command "printf cli"}))))',
		undefined,
		false,
	],
	[
		"Clojure library",
		[
			'(defsession answer [] (+ 20 22)) (store "cursor" "cli")',
			'(text (session/answer)) (text (load "cursor"))',
		],
		undefined,
		false,
	],
	["JavaScript input", 'text("JS EXECUTED");', undefined, true],
	["JavaScript input with an obsolete selector", 'text("JS EXECUTED");', "javascript", true],
] as const) {
	test(`the installed Pi binary ${rejected ? "rejects" : "executes"} ${name} through its real worker`, () => {
		const cwd = mkdtempSync(join(tmpdir(), "pi-sci-cli-"));
		try {
			const entrypoint = fileURLToPath(
				new URL("../../../pi/agent/extensions/sci-codemode/index.ts", import.meta.url),
			);
			const settings = JSON.parse(
				readFileSync(new URL("../../../pi/agent/settings.json", import.meta.url), "utf8"),
			);
			if (legacyLanguage !== undefined) settings.codemode.language = legacyLanguage;
			writeFileSync(join(cwd, "settings.json"), JSON.stringify(settings));
			const directory = join(cwd, "extensions", "sci-codemode");
			mkdirSync(directory, { recursive: true });
			symlinkSync(entrypoint, join(directory, "index.ts"));
			const result = spawnSync(
				process.env.PI_SCI_TEST_BINARY ?? "pi",
				[
					"--offline",
					"--no-skills",
					"--no-prompt-templates",
					"--no-context-files",
					"--no-session",
					"--mode",
					"json",
					"--print",
					"--tools",
					"read,bash,edit,write,codemode",
					"--provider",
					"sci-fixture",
					"--model",
					"chat",
					"--extension",
					fileURLToPath(new URL("./provider.ts", import.meta.url)),
					"Run the fixture program.",
				],
				{
					cwd,
					env: {
						...process.env,
						PI_CODING_AGENT_DIR: cwd,
						PI_SCI_TEST_SCRIPTS: JSON.stringify(typeof source === "string" ? [source] : source),
					},
					encoding: "utf8",
					timeout: 30000,
					maxBuffer: 2 * 1024 * 1024,
				},
			);
			assert.equal(result.status, 0, result.stderr + result.stdout);
			const events = result.stdout
				.split("\n")
				.filter(Boolean)
				.map((line) => JSON.parse(line));
			const messages = events.filter(
				(event) => event.type === "message_end" && event.message.role === "toolResult",
			);
			assert.equal(messages.length, typeof source === "string" ? 1 : source.length);
			const message = messages.at(-1)?.message;
			assert.ok(message, result.stderr + result.stdout);
			assert.equal(message.isError, rejected, result.stderr + result.stdout);
			const output = message.content
				.filter((block: { type: string }) => block.type === "text")
				.map((block: { text: string }) => block.text)
				.join("\n");
			if (rejected) {
				assert.match(output, /Script error|Script failed/);
				assert.doesNotMatch(output, /JS EXECUTED/);
			} else {
				assert.match(output, /42/);
				assert.match(output, /cli/);
			}
			assert.doesNotMatch(result.stderr, /Failed to load|conflict|Error/);
		} finally {
			rmSync(cwd, { recursive: true, force: true });
		}
	});
}

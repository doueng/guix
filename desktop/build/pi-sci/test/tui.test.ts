import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

test("the actual Pi TUI renders the SCI call and output", () => {
	const cwd = mkdtempSync(join(tmpdir(), "pi-sci-tui-"));
	try {
		writeFileSync(
			join(cwd, "settings.json"),
			JSON.stringify({ codemode: { mode: "only" }, tuiMode: "regular" }),
		);
		const extension = join(cwd, "extension.ts");
		symlinkSync(
			fileURLToPath(new URL("../../../pi/agent/extensions/sci-codemode/index.ts", import.meta.url)),
			extension,
		);
		const result = spawnSync(
			"python3",
			[
				fileURLToPath(new URL("./tui.py", import.meta.url)),
				process.env.PI_SCI_TEST_BINARY ?? "pi",
				"--offline",
				"--no-extensions",
				"--no-skills",
				"--no-prompt-templates",
				"--no-context-files",
				"--no-session",
				"--provider",
				"sci-fixture",
				"--model",
				"chat",
				"--tools",
				"read,bash,edit,write,codemode",
				"--extension",
				extension,
				"--extension",
				fileURLToPath(new URL("./provider.ts", import.meta.url)),
				"Run the fixture program.",
			],
			{
				cwd,
				env: {
					...process.env,
					PI_CODING_AGENT_DIR: cwd,
					PI_SCI_TEST_SCRIPTS: JSON.stringify(["(text (+ 20 22))"]),
				},
				encoding: "utf8",
				timeout: 30000,
				maxBuffer: 3 * 1024 * 1024,
			},
		);
		assert.equal(result.status, 0, result.stderr + result.stdout);
		assert.match(result.stdout, /codemode.*SCI/);
		assert.match(result.stdout, /42/);
		assert.match(result.stdout, /fixture complete/);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

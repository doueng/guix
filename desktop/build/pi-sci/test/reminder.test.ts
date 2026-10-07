import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { DefaultResourceLoader, type ExtensionContext } from "@earendil-works/pi-coding-agent";

test("the real Jev extension emits only a SCI reminder after compaction", async () => {
	const cwd = mkdtempSync(join(tmpdir(), "pi-sci-reminder-"));
	try {
		const loader = new DefaultResourceLoader({
			cwd,
			agentDir: cwd,
			noExtensions: true,
			noSkills: true,
			noThemes: true,
			noContextFiles: true,
			noPromptTemplates: true,
			additionalExtensionPaths: [
				fileURLToPath(new URL("../../../pi/agent/extensions/jev-pstack.ts", import.meta.url)),
			],
		});
		await loader.reload();
		const loaded = loader.getExtensions();
		assert.deepEqual(loaded.errors, []);
		loaded.runtime.getActiveTools = () => [];
		loaded.runtime.setActiveTools = () => {};
		loaded.runtime.appendEntry = () => {};
		loaded.runtime.getSettings = () => {
			throw new Error("The reminder must not consult a language selector");
		};
		const extension = loaded.extensions.find((item) => item.tools.has("jev_classify_task"));
		assert.ok(extension);
		const input = extension.handlers.get("input")?.[0];
		const compact = extension.handlers.get("session_compact")?.[0];
		const start = extension.handlers.get("before_agent_start")?.[0];
		assert.ok(input && compact && start);
		const ctx = { ui: { setStatus() {} } } as unknown as ExtensionContext;
		await input({ type: "input", source: "interactive", text: "/skill:poteto-mode" }, ctx);
		await compact({ type: "session_compact" }, ctx);
		const result = (await start({ type: "before_agent_start" }, ctx)) as
			| { message?: { content: string } }
			| undefined;
		assert.ok(result?.message);
		assert.match(result.message.content, /poteto-mode is still active/);
		assert.ok(
			result.message.content.includes(
				'(text (await (tools/jev_classify_task {:task "<user task>"})))',
			),
		);
		assert.doesNotMatch(result.message.content, /text\(await tools\./);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

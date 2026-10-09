import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { DefaultResourceLoader, type ExtensionContext } from "@earendil-works/pi-coding-agent";

type Handler = (event: unknown, ctx: ExtensionContext) => Promise<unknown>;

async function loadJev(cwd: string, entries: unknown[]) {
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
	const appended: unknown[] = [];
	loaded.runtime.getActiveTools = () => [];
	loaded.runtime.setActiveTools = () => {};
	loaded.runtime.appendEntry = (_type: string, data: unknown) => {
		appended.push(data);
	};
	const extension = loaded.extensions.find((item) => item.tools.has("jev_classify_task"));
	assert.ok(extension);
	const start = extension.handlers.get("session_start")?.[0] as Handler | undefined;
	const input = extension.handlers.get("input")?.[0] as Handler | undefined;
	assert.ok(start && input);
	const ctx = {
		ui: { setStatus() {} },
		sessionManager: { getBranch: () => entries },
	} as unknown as ExtensionContext;
	await start({ type: "session_start", reason: "startup" }, ctx);
	const send = (text: string) => input({ type: "input", source: "interactive", text }, ctx);
	return { appended, send };
}

test("a new session loads poteto-mode with its first plain prompt", async () => {
	const cwd = mkdtempSync(join(tmpdir(), "pi-sci-poteto-"));
	try {
		const { appended, send } = await loadJev(cwd, [{ type: "model_change" }]);
		assert.deepEqual(await send("/model"), { action: "continue" });
		assert.deepEqual(await send("fix the build"), {
			action: "transform",
			text: "/skill:poteto-mode fix the build",
		});
		assert.deepEqual(appended, [{ active: true }]);
		assert.deepEqual(await send("continue"), { action: "continue" });
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

test("resumed sessions and recorded choices are left alone", async () => {
	const cwd = mkdtempSync(join(tmpdir(), "pi-sci-poteto-"));
	try {
		const resumed = await loadJev(cwd, [{ type: "message" }]);
		assert.deepEqual(await resumed.send("hello"), { action: "continue" });
		const off = await loadJev(cwd, [
			{ type: "custom", customType: "poteto-mode", data: { active: false } },
		]);
		assert.deepEqual(await off.send("hello"), { action: "continue" });
		assert.deepEqual([...resumed.appended, ...off.appended], []);
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
});

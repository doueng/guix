import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
	createAgentSession,
	DefaultResourceLoader,
	type ExtensionFactory,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";
import sciCodemode from "../src/extension.ts";
import { fixtureProvider } from "./provider.ts";

export async function harness(
	extra: ExtensionFactory[] = [],
	persistent = false,
	codemode: Record<string, unknown> = { mode: "only" },
) {
	const cwd = mkdtempSync(join(tmpdir(), "pi-sci-session-"));
	const settings = SettingsManager.inMemory(
		JSON.parse(
			JSON.stringify({
				codemode,
				defaultTools: ["+codemode"],
			}),
		),
	);
	let pending: string | undefined;
	const loader = new DefaultResourceLoader({
		cwd,
		agentDir: cwd,
		settingsManager: settings,
		noExtensions: true,
		noSkills: true,
		noThemes: true,
		noContextFiles: true,
		noPromptTemplates: true,
		extensionFactories: [
			sciCodemode,
			fixtureProvider(() => {
				const source = pending;
				pending = undefined;
				return source;
			}),
			...extra,
		],
	});
	await loader.reload();
	assert.deepEqual(loader.getExtensions().errors, []);
	const manager = persistent
		? SessionManager.create(cwd, join(cwd, "sessions"))
		: SessionManager.inMemory(cwd);
	const { session } = await createAgentSession({
		cwd,
		agentDir: cwd,
		resourceLoader: loader,
		settingsManager: settings,
		sessionManager: manager,
	});
	await session.bindExtensions({});
	const model = session.modelRuntime.getModel("sci-fixture", "chat");
	assert.ok(model);
	await session.setModel(model);
	return {
		cwd,
		manager,
		session,
		call: async (code: string) => {
			pending = code;
			await session.prompt("Run the fixture program.");
			const result = session.messages.findLast(
				(message) => message.role === "toolResult" && message.toolName === "codemode",
			);
			assert.ok(result && result.role === "toolResult", "the agent produced a codemode result");
			return result;
		},
		close: () => {
			session.dispose();
			rmSync(cwd, { recursive: true, force: true });
		},
	};
}

export function output(result: Awaited<ReturnType<Awaited<ReturnType<typeof harness>>["call"]>>) {
	return result.content
		.filter((item) => item.type === "text")
		.map((item) => item.text)
		.join("\n");
}

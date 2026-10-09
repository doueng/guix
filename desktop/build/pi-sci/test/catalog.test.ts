import assert from "node:assert/strict";
import { test } from "node:test";
import type { ToolLoadout } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { capabilities, DESCRIPTION_HEAD, sciDescription, toolSignature } from "../src/catalog.ts";
import { DOMAINS } from "../src/commands.ts";
import { signatures } from "../src/operations.ts";

function tools(names: string[]): ToolLoadout["callable"] {
	return names.map((name) => ({
		name,
		label: name,
		description: name,
		parameters: Type.Object({}),
		execute: async () => ({ content: [{ type: "text" as const, text: name }], details: undefined }),
	}));
}

test("capability identifiers use Pi's exact normalization, including initial digits", () => {
	const entries = capabilities(tools(["9lookup", "my-tool", "", "codemode"]), () => undefined);
	assert.deepEqual(
		entries.map(({ id, name, symbol }) => ({ id, name, symbol })),
		[
			{ id: "9lookup", name: "_lookup", symbol: "tools/_lookup" },
			{ id: "my-tool", name: "my_tool", symbol: "tools/my_tool" },
			{ id: "", name: "_", symbol: "tools/_" },
		],
	);
});

test("signatures for the main domains and plain tools survive a 2048-character cut", () => {
	const extra = Array.from({ length: 40 }, (_, i) => `extra_tool_${i}`);
	const callable = tools([...DOMAINS.map((domain) => domain.id), "edit", "bash", ...extra, "vent"]);
	callable[DOMAINS.length].parameters = Type.Object({
		path: Type.String(),
		edits: Type.Array(Type.Object({ oldText: Type.String(), newText: Type.String() })),
	});
	const entries = capabilities(callable, () => undefined);
	const loadout = { getExposure: () => "codemode" } as unknown as ToolLoadout;
	const description = sciDescription(loadout, entries, 3000, true);
	const head = description.slice(0, DESCRIPTION_HEAD);
	assert.match(head, /^Run restricted Clojure/);
	const main = DOMAINS.filter((domain) => ["fs", "search", "sys", "jj"].includes(domain.id));
	for (const line of main
		.flatMap(signatures)
		.filter((line) => !/\/(version|describe)\)/.test(line)))
		assert.ok(head.includes(line), line);
	assert.ok(head.includes("(tools/edit {:path! :edits! [{:oldText! :newText!}]})"));
	assert.ok(head.includes("(catalog/describe"));
	assert.ok(head.includes("(tools/vent)"), "core tools outrank extra tools");
	assert.ok(head.includes("(make/run targets"), "make outranks extra tools");
	assert.ok(!head.includes("(jj/version)"), "version functions come last");
	const plain = entries.filter((entry) => !DOMAINS.some((domain) => domain.id === entry.id));
	for (const line of [...DOMAINS.flatMap(signatures), ...plain.map(toolSignature)])
		assert.ok(description.includes(line), line);
	assert.doesNotMatch(description, /Input JSON Schema/);
});

test("colliding capability identifiers fail instead of dispatching to the wrong tool", () => {
	assert.throws(
		() => capabilities(tools(["my-tool", "my.tool"]), () => undefined),
		/identifier collision/,
	);
});

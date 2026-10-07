import assert from "node:assert/strict";
import { test } from "node:test";
import type { ToolLoadout } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { capabilities } from "../src/catalog.ts";

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

test("colliding capability identifiers fail instead of dispatching to the wrong tool", () => {
	assert.throws(
		() => capabilities(tools(["my-tool", "my.tool"]), () => undefined),
		/identifier collision/,
	);
});

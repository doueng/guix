import assert from "node:assert/strict";
import fs from "node:fs";
import { syncBuiltinESMExports } from "node:module";
import { test } from "node:test";
import type { ExtensionAPI, ToolDefinition } from "@earendil-works/pi-coding-agent";
import sciCodemode from "../src/extension.ts";
import { compileSciSource, SCI_GRAMMAR } from "../src/source.ts";

test("codemode registers only Clojure without reading a language setting", () => {
	let definition: ToolDefinition | undefined;
	sciCodemode({
		registerTool(tool: ToolDefinition) {
			definition = tool;
		},
		on() {
			return () => {};
		},
		registerCommand() {},
		getSettings() {
			throw new Error("Settings are unavailable during extension loading");
		},
	} as unknown as ExtensionAPI);
	assert.ok(definition);
	assert.match(definition.description, /Run restricted Clojure/);
	assert.equal(definition.promptSnippet, "Run Clojure that calls other tools");
	assert.deepEqual(definition.constrainedSampling, {
		type: "grammar",
		variants: { openai_lark: SCI_GRAMMAR },
	});
	assert.doesNotMatch(
		definition.promptGuidelines?.join("\n") ?? "",
		/JavaScript|Promise\.allSettled/,
	);
});

test("an unavailable SCI bundle errors instead of executing the input as JavaScript", (t) => {
	t.mock.method(fs, "readFileSync", () => {
		throw new Error("SCI bundle unavailable");
	});
	syncBuiltinESMExports();
	try {
		assert.throws(
			() => compileSciSource('text("JS MUST NOT RUN");'),
			/SCI bundle could not be loaded\. Run make pi-sci/,
		);
	} finally {
		t.mock.restoreAll();
		syncBuiltinESMExports();
	}
});

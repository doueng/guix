import assert from "node:assert/strict";
import { test } from "node:test";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import {
	ENVIRONMENT_RUNTIME,
	environmentDefinitions,
	parseEnvironmentDelta,
	readEnvironment,
} from "../src/environment.ts";

const definition = {
	op: "define",
	name: "x",
	source: "(defsession x 1)",
	kind: "value",
	parameters: null,
	async: false,
};
const delta = (operations: unknown[], reset = false) => ({
	version: 1,
	runtime: ENVIRONMENT_RUNTIME,
	reset,
	operations,
});

test("journal reconstruction respects branch ancestry, forgetting, and explicit reset", () => {
	const manager = SessionManager.inMemory("/tmp");
	manager.appendCustomEntry("codemode-store", {
		set: {},
		delete: [],
		sciEnvironment: delta([definition]),
	});
	const root = manager.getLeafId();
	assert.ok(root);
	const first = readEnvironment(manager.getBranch());
	manager.appendCustomEntry("codemode-store", {
		set: {},
		delete: [],
		sciEnvironment: delta([{ op: "forget", name: "x" }]),
	});
	assert.deepEqual(environmentDefinitions(readEnvironment(manager.getBranch())), []);
	manager.branch(root);
	assert.deepEqual(readEnvironment(manager.getBranch()), first);
	assert.equal(environmentDefinitions(first)[0]?.source, "(defsession x 1)");
	manager.appendCustomEntry("codemode-store", {
		set: {},
		delete: [],
		sciEnvironment: delta([], true),
	});
	assert.deepEqual(readEnvironment(manager.getBranch()).operations, []);
});

test("malformed and incompatible journals fail closed, but explicit reset recovers them", () => {
	assert.throws(() => parseEnvironmentDelta({ ...delta([]), runtime: "other" }), /Incompatible/);
	assert.throws(
		() => parseEnvironmentDelta(delta([{ ...definition, name: "tools/read" }])),
		/name/,
	);
	assert.throws(() => parseEnvironmentDelta(delta([{ ...definition, source: "" }])), /declaration/);
	const manager = SessionManager.inMemory("/tmp");
	manager.appendCustomEntry("codemode-store", { sciEnvironment: { version: 900 } });
	assert.throws(() => readEnvironment(manager.getBranch()), /Incompatible/);
	manager.appendCustomEntry("codemode-store", {
		set: {},
		delete: [],
		sciEnvironment: delta([], true),
	});
	assert.deepEqual(readEnvironment(manager.getBranch()).operations, []);
});

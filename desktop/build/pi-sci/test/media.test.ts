import assert from "node:assert/strict";
import { test } from "node:test";
import { CodemodeSandbox } from "@earendil-works/pi-codemode";
import { compileSciSource } from "../src/source.ts";
import { PNG } from "./provider.ts";

test("SCI forwards image blocks without printing their base64 payload", async () => {
	const sandbox = new CodemodeSandbox({
		tools: [
			{ name: "image", execute: () => ({ type: "image", data: PNG, mimeType: "image/png" }) },
		],
	});
	try {
		const result = await sandbox.execute(compileSciSource("(image (await (tools/image {})))"));
		assert.equal(result.ok, true, JSON.stringify(result));
		assert.deepEqual(result.output, [{ type: "image", data: PNG, mimeType: "image/png" }]);
	} finally {
		await sandbox.close();
	}
});

test("remote image URLs remain forbidden", async () => {
	const sandbox = new CodemodeSandbox();
	try {
		const result = await sandbox.execute(
			compileSciSource('(image "https://example.com/image.png")'),
		);
		assert.equal(result.ok, false);
		assert.match(!result.ok ? result.error.message : "", /base64|data:/);
	} finally {
		await sandbox.close();
	}
});

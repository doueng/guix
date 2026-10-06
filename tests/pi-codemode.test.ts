import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { createTokenjuicePiExtension } from "../desktop/pi/agent/extensions/tokenjuice/src/hosts/pi/extension/runtime.ts";
import type { Pi, PiContext, PiToolResultEvent } from "../desktop/pi/agent/extensions/tokenjuice/src/hosts/pi/extension/pi-types.ts";

function harness(cwd: string) {
  const handlers = new Map<string, (event: unknown, ctx: PiContext) => unknown>();
  let command: Parameters<Pi["registerCommand"]>[1] | undefined;
  createTokenjuicePiExtension({ extensionCommand: "tj" })({
    on: (name, handler) => { handlers.set(name, handler); },
    registerCommand: (_name, definition) => { command = definition; },
    appendEntry: () => {},
  });
  const ctx: PiContext = {
    cwd, hasUI: false,
    sessionManager: { getBranch: () => [] },
    ui: { notify: () => {} },
  };
  return {
    result: async (event: PiToolResultEvent) => handlers.get("tool_result")!(event, ctx),
    rawNext: async () => command!.handler("raw-next", ctx),
  };
}

function bashEvent(parentToolCallId?: string) {
  const output = Array.from({ length: 200 }, (_, i) => ` ✓ src/test-${i}.test.ts (1 test) 1ms`).join("\n")
    + "\n Test Files  200 passed (200)\n      Tests  200 passed (200)\n   Duration  1s\n";
  return {
    toolName: "bash", input: { command: "vitest run" },
    content: [{ type: "text", text: output }],
    structuredContent: { output, exit_code: 0, truncated: false, wall_time_seconds: 1 },
    ...(parentToolCallId === undefined ? {} : { parentToolCallId }),
  };
}

test("Pi defaults keep codemode active in only mode", () => {
  const settings = JSON.parse(readFileSync(new URL("../desktop/pi/agent/settings.json", import.meta.url), "utf8"));
  assert.ok(settings.defaultTools.includes("+codemode"));
  assert.equal(settings.codemode.mode, "only");
});

test("Tokenjuice preserves nested bash results but still compacts direct results", async () => {
  const cwd = mkdtempSync(join(tmpdir(), "pi-codemode-"));
  const previousStats = process.env.TOKENJUICE_STATS;
  process.env.TOKENJUICE_STATS = "0";
  try {
    const runtime = harness(cwd);
    const nested = bashEvent("codemode-call");
    assert.equal(await runtime.result(nested), undefined);
    assert.equal(nested.structuredContent.output, nested.content[0].text);
    const direct = await runtime.result(bashEvent()) as { content: { text: string }[] };
    assert.match(direct.content[0].text, /tokenjuice compacted bash output/);
    assert.ok(direct.content[0].text.length < nested.content[0].text.length);
  } finally {
    if (previousStats === undefined) delete process.env.TOKENJUICE_STATS;
    else process.env.TOKENJUICE_STATS = previousStats;
    rmSync(cwd, { recursive: true, force: true });
  }
});

test("nested results do not consume Tokenjuice raw-next", async () => {
  const cwd = mkdtempSync(join(tmpdir(), "pi-codemode-"));
  try {
    const runtime = harness(cwd);
    await runtime.rawNext();
    assert.equal(await runtime.result(bashEvent("codemode-call")), undefined);
    const direct = await runtime.result(bashEvent()) as { content: { text: string }[] };
    assert.ok(direct.content[0].text.startsWith(bashEvent().content[0].text));
    assert.match(direct.content[0].text, /bypass/);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

test("nested results never read Tokenjuice full-output artifacts", async () => {
  const cwd = mkdtempSync(join(tmpdir(), "pi-codemode-"));
  try {
    const event = {
      ...bashEvent("codemode-call"),
      details: { fullOutputPath: join(cwd, "missing-output.txt") },
    };
    assert.equal(await harness(cwd).result(event), undefined);
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
});

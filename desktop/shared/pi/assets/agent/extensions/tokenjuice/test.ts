import assert from "node:assert/strict";
import { cp, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { reduceExecution } from "./src/core/reduce.js";
import { storeArtifact } from "./src/core/artifacts.js";
import { buildTokenjuiceDetails } from "./src/pi-extension/tool-result.js";

async function test(name: string, fn: () => Promise<void>) {
  try {
    await fn();
    console.log(`ok - ${name}`);
  } catch (error) {
    console.error(`not ok - ${name}`);
    throw error;
  }
}

const tempRoot = await mkdtemp(join(tmpdir(), "tokenjuice-vendored-test-"));
const rulesDir = join(tempRoot, ".config", "tokenjuice", "rules");
const repoRulesDir = resolve(process.cwd(), "../../../../../../../dendritic/features/tokenjuice/assets/rules");
process.env.HOME = tempRoot;
process.chdir(tempRoot);
await cp(repoRulesDir, rulesDir, { recursive: true });

await test("jj status uses custom project rule", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "jj status",
    argv: ["jj", "status"],
    combinedText: [
      "Working copy  (@) : qpvuntsm 12345678 my-change",
      "Parent commit (@-): abcdef12 parent",
      "A new-file.txt",
      "M changed-file.nix",
      "D old-file.txt",
      "Hint: use jj diff to inspect changes",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "jj/status");
  assert.match(result.inlineText, /new-file\.txt/);
  assert.doesNotMatch(result.inlineText, /Hint:/);
});

await test("nix cli uses custom project rule", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "nix flake check",
    argv: ["nix", "flake", "check"],
    combinedText: [
      "warning: Git tree '/tmp/repo' is dirty",
      "evaluating flake...",
      "checking flake output 'packages'",
      "Finished successfully",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "nix/cli");
  assert.doesNotMatch(result.inlineText, /Git tree/);
  assert.match(result.inlineText, /evaluating flake/);
});

await test("nix cli failure preserves actionable final error block", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "nix eval .#nixosConfigurations.openclaw.config.system.build.toplevel.drvPath",
    argv: ["nix", "eval", ".#nixosConfigurations.openclaw.config.system.build.toplevel.drvPath"],
    exitCode: 1,
    combinedText: [
      "warning: Git tree '/tmp/repo' is dirty",
      "copying path '/nix/store/noise-1' from 'https://cache.nixos.org'...",
      "copying path '/nix/store/noise-2' from 'https://cache.nixos.org'...",
      "error:",
      "       … while calling the 'seq' builtin",
      "       … while evaluating the option `nixpkgs.system':",
      "       error: Neither nixpkgs.hostPlatform nor the legacy option nixpkgs.system has been set.",
      "       You can set nixpkgs.hostPlatform in hardware-configuration.nix by re-running",
      "       a recent version of nixos-generate-config.",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "nix/cli");
  assert.match(result.inlineText, /Neither nixpkgs\.hostPlatform/);
  assert.match(result.inlineText, /nixos-generate-config/);
  assert.doesNotMatch(result.inlineText, /copying path/);
});

await test("nh uses custom project rule", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "nh os build .",
    argv: ["nh", "os", "build", "."],
    combinedText: [
      "building /nix/store/example.drv...",
      "warning: low disk space",
      "Done!",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "nix/nh");
  assert.match(result.inlineText, /Done!/);
});

await test("nixos-rebuild uses custom project rule", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "nixos-rebuild switch --flake .#host",
    argv: ["nixos-rebuild", "switch", "--flake", ".#host"],
    combinedText: [
      "building the system configuration...",
      "activating the configuration...",
      "switching to system configuration /nix/store/xyz",
      "Done!",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "nix/rebuild");
  assert.match(result.inlineText, /activating/);
});

await test("bazel uses custom project rule", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "bazel test //app:unit",
    argv: ["bazel", "test", "//app:unit"],
    combinedText: [
      "Loading: 0 packages loaded",
      "INFO: Analyzed target //app:unit (0 packages loaded, 0 targets configured).",
      "FAIL: //app:unit (see /tmp/test.log)",
      "INFO: Build did NOT complete successfully",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "build/bazel");
  assert.match(result.inlineText, /FAIL:/);
  assert.doesNotMatch(result.inlineText, /Analyzed target/);
});

await test("maven uses custom project rule", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "./mvnw test",
    argv: ["mvnw", "test"],
    combinedText: [
      "[INFO] Downloading from repo",
      "[ERROR] Tests run: 12, Failures: 1, Errors: 0",
      "[INFO] BUILD FAILURE",
      "[INFO] Reactor Summary",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "build/maven");
  assert.match(result.inlineText, /BUILD FAILURE/);
  assert.doesNotMatch(result.inlineText, /Downloading/);
});

await test("repo make rule preserves nix-relevant lines", async () => {
  const result = await reduceExecution({
    toolName: "exec",
    command: "make eval",
    argv: ["make", "eval"],
    combinedText: [
      "make[1]: Entering directory '/home/engstrand/nixos'",
      "warning: Git tree '/tmp/repo' is dirty",
      "evaluating flake...",
      "checking flake output 'nixosConfigurations'...",
      "error: path '/nix/store/example' is not valid",
      "make: *** [Makefile:126: eval] Error 1",
      "make[1]: Leaving directory '/home/engstrand/nixos'",
    ].join("\n"),
  }, { cwd: process.cwd(), maxInlineChars: 1200, store: false, recordStats: false });
  assert.equal(result.classification.matchedReducer, "task/make");
  assert.match(result.inlineText, /evaluating flake/);
  assert.match(result.inlineText, /checking flake output 'nixosConfigurations'/);
  assert.match(result.inlineText, /error: path/);
  assert.match(result.inlineText, /make: \*\*\*/);
  assert.doesNotMatch(result.inlineText, /Entering directory/);
  assert.doesNotMatch(result.inlineText, /Git tree/);
});

await test("artifact storage writes raw filtered and diff outputs", async () => {
  const ref = await storeArtifact(
    {
      input: { toolName: "exec", command: "dummy", argv: ["dummy"] },
      rawText: "line1\nline2\nline3\n",
      filteredText: "line1\nline3\n",
      classification: { family: "test", confidence: 1, matchedReducer: "test/manual" },
      stats: { rawChars: 18, reducedChars: 12, ratio: 12 / 18 },
    },
    join(process.cwd(), ".tmp-tokenjuice-tests"),
  );
  assert.ok(ref.path);
  assert.ok(ref.filteredTextPath);
  assert.ok(ref.diffPath);
});

await test("tokenjuice details expose artifact and diff paths", async () => {
  const ref = await storeArtifact(
    {
      input: { toolName: "exec", command: "dummy", argv: ["dummy"] },
      rawText: "raw\nextra\n",
      filteredText: "raw\n",
      classification: { family: "test", confidence: 1, matchedReducer: "test/manual" },
      stats: { rawChars: 10, reducedChars: 4, ratio: 0.4 },
    },
    join(process.cwd(), ".tmp-tokenjuice-tests"),
  );
  const details = buildTokenjuiceDetails({
    inlineText: "raw",
    rawRef: ref,
    stats: { rawChars: 10, reducedChars: 4, ratio: 0.4 },
    classification: { family: "test", confidence: 1, matchedReducer: "test/manual" },
  }, "/tmp/full-output.txt");
  assert.equal(details.rawArtifactId, ref.id);
  assert.equal(details.rawArtifactPath, ref.path);
  assert.equal(details.filteredTextPath, ref.filteredTextPath);
  assert.equal(details.diffPath, ref.diffPath);
  assert.equal(details.fullOutputPath, "/tmp/full-output.txt");
});

await rm(tempRoot, { recursive: true, force: true });
console.log("all tests passed");

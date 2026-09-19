import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { gitGuardDecision } from "./guard-policy.ts";
import { librarianCheckoutRoot } from "./shell-guard.ts";

const root = mkdtempSync(join(tmpdir(), "pi-guard-test-"));
const jjRoot = join(root, "workspace");
const outside = join(root, "outside");
mkdirSync(join(jjRoot, ".jj"), { recursive: true });
mkdirSync(outside, { recursive: true });

try {
	assert.deepEqual(gitGuardDecision("git status", jjRoot), { block: true, reason: "jj-workspace" });
	assert.deepEqual(gitGuardDecision("env FOO=1 git diff", jjRoot), { block: true, reason: "jj-workspace" });
	assert.deepEqual(gitGuardDecision("command /usr/bin/git log", jjRoot), { block: true, reason: "jj-workspace" });
	assert.deepEqual(gitGuardDecision("python -c \"print('/nix/store/example/git')\"", jjRoot), { block: false });
	assert.deepEqual(gitGuardDecision("ls /nix/store/*git*", jjRoot), { block: false });
	assert.deepEqual(gitGuardDecision(`cd ${outside} && git status`, jjRoot), { block: false });

	const checkout = join(librarianCheckoutRoot(), "github.com", "example", "repo");
	assert.deepEqual(gitGuardDecision(`git -C ${checkout} status`, jjRoot), { block: false });
	assert.deepEqual(gitGuardDecision(`cd ${checkout} && git show HEAD`, jjRoot), { block: false });
	assert.deepEqual(gitGuardDecision(`git -C ${checkout} reset --hard`, jjRoot), { block: true, reason: "librarian-write" });
} finally {
	rmSync(root, { recursive: true, force: true });
}

console.log("pi shell guard tests: pass");

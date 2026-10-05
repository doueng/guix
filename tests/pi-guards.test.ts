import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, rmSync, symlinkSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import jjGuardExtension from "../desktop/pi/agent/extensions/jj-guard.ts";
import { gitGuardDecision } from "../desktop/pi/agent/extensions/lib/guard-policy.ts";
import { librarianCheckoutRoot } from "../desktop/pi/agent/extensions/lib/shell-guard.ts";

const fixture = mkdtempSync(join(tmpdir(), "pi-guards-"));
const jj = join(fixture, "jj");
const external = join(fixture, "external");
const cache = join(librarianCheckoutRoot(), "github.com", "example", "repo");
mkdirSync(join(jj, ".jj"), { recursive: true });
mkdirSync(external);
symlinkSync(homedir(), join(fixture, "home"));
symlinkSync(join(homedir(), "pi-guard-nonexistent-target"), join(fixture, "dangling"));
after(() => rmSync(fixture, { recursive: true, force: true }));

function allowed(command: string, cwd = jj) {
	assert.deepEqual(gitGuardDecision(command, cwd), { block: false });
}
function blocked(command: string, reason: "jj-workspace" | "librarian-write", cwd = jj) {
	assert.deepEqual(gitGuardDecision(command, cwd), { block: true, reason });
}

test("literal -C targets outside jj remain usable", () => {
	allowed(`git -C '${external}' show HEAD`);
	allowed(`git -C '${cache}' diff HEAD~ HEAD`);
	blocked("git status", "jj-workspace");
	blocked(`git -C '${jj}' show HEAD`, "jj-workspace", external);
});

test("literal shell variables resolve -C targets", () => {
	allowed(`C=${cache}; git -C $C archive HEAD pstack | tar -x -C /tmp/ps/base`);
	allowed(`C=~/.cache/checkouts/github.com/example/repo; git -C \"$C\" log --oneline`);
	allowed(`C='${external}'; git -C \"\${C}\" show HEAD`);
	blocked(`C=${jj}; git -C $C show HEAD`, "jj-workspace", external);
	blocked(`C=${external}; git -C '$C' show HEAD`, "jj-workspace");
	blocked(`C=${external}; git -C \\$C show HEAD`, "jj-workspace");
});

test("read-only cached exports, including attached redirects", () => {
	for (const command of [
		"git archive HEAD pstack | tar -x -C /tmp/ps/base",
		"git archive HEAD >/tmp/base.tar",
		"git archive HEAD > /tmp/base.tar 2>&1",
		"git show HEAD:README.md >/dev/null",
		"git diff HEAD~ HEAD | cat > /tmp/delta.patch",
		"git archive --output=/tmp/base.tar HEAD",
		"git archive -o /tmp/base.tar HEAD",
		"git archive -o/tmp/base.tar HEAD",
		"git show HEAD:README.md | diff - /tmp/README.md",
		"git diff HEAD~ HEAD > /tmp/delta.patch",
		"git diff --output=/tmp/delta.patch HEAD~ HEAD",
		"git ls-tree --name-only HEAD pstack/skills",
		"git worktree list | head -2",
		"git tag --sort=-version:refname",
	]) allowed(command, cache);
});

test("cache mutation and unsafe output destinations remain blocked", () => {
	for (const command of [
		"git archive HEAD > archive.tar",
		"git archive HEAD >> archive.tar",
		"git archive HEAD 2> error.log",
		"git archive -o archive.tar HEAD",
		"git archive --output=archive.tar HEAD",
		"git diff --output=delta.patch HEAD~ HEAD",
		"git show HEAD > README.md",
		"git show HEAD | cat > README.md",
		"git archive HEAD > /tmp/../home/archive.tar",
		"git archive HEAD > /tmp/$UNKNOWN/archive.tar",
		"git commit -m test",
		"git tag new-tag",
		"git worktree add /tmp/worktree",
		"git worktree remove /tmp/worktree",
		"git status; git reset --hard",
	]) blocked(command, "librarian-write", cache);
	blocked(`git -C '${cache}' archive -o base.tar HEAD`, "librarian-write", fixture);
	blocked(`git archive HEAD > '${join(fixture, "home", "archive.tar")}'`, "librarian-write", cache);
	blocked(`git archive HEAD > '${join(fixture, "dangling")}'`, "librarian-write", cache);
});

test("-C in a pathspec must not change the git target", () => {
	blocked(`git show HEAD -- -C '${external}'`, "jj-workspace");
});

test("quoted shell operators and nested shell invocations", () => {
	allowed(`git -C '${cache}' show 'HEAD:file|name'`);
	allowed(`bash -lc 'cd ${cache} && git archive HEAD > /tmp/base.tar'`);
	blocked(`bash -lc 'cd ${cache} && git reset --hard'`, "librarian-write");
	blocked(`bash -lc 'git -C ${cache} show HEAD' > README.md`, "librarian-write", cache);
});

test("extension hook allows exports and reports unsafe writes without a UI", async () => {
	type Handler = (event: { toolName: string; input: { command: string } }, ctx: { cwd: string; hasUI: boolean }) => unknown;
	let handler: Handler | undefined;
	jjGuardExtension({ on: (name: string, callback: Handler) => {
		assert.equal(name, "tool_call");
		handler = callback;
	} } as never);
	assert.ok(handler);
	assert.equal(await handler({ toolName: "bash", input: { command: `git -C ${cache} archive HEAD >/tmp/base.tar` } },
		{ cwd: jj, hasUI: false }), undefined);
	const result = await handler({ toolName: "bash", input: { command: "git archive HEAD > README.md" } },
		{ cwd: cache, hasUI: false }) as { block: boolean; reason: string };
	assert.equal(result.block, true);
	assert.match(result.reason, /stdout or under \/tmp/u);
});

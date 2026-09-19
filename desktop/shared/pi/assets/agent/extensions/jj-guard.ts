import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

import { gitGuardDecision, type GitGuardDecision } from "./lib/guard-policy.ts";

function buildReason(command: string, reason: GitGuardDecision["reason"]): string {
	if (reason === "librarian-write") {
		return [
			"Only read-only git inspection is allowed in librarian-managed checkouts.",
			"Create a task-specific worktree or copy before changing upstream repositories.",
			"",
			`Blocked command: ${command}`,
		].join("\n");
	}
	return [
		"git is disabled for pi-agent shell commands inside Jujutsu repos.",
		"Use jj instead.",
		"",
		"Common replacements:",
		"- git status -> jj status",
		"- git diff -> jj diff --git",
		"- git log -> jj log",
		"- git show -> jj show",
		"- git commit -> don't commit",
		"- git add -> no staging in jj; just edit files, then use jj commit",
		"",
		`Blocked command: ${command}`,
	].join("\n");
}

export default function jjGuardExtension(pi: ExtensionAPI) {
	pi.on("tool_call", async (event, ctx) => {
		if (event.toolName !== "bash") return undefined;
		const command = String(event.input.command ?? "");
		const decision = gitGuardDecision(command, ctx.cwd);
		if (!decision.block) return undefined;

		if (ctx.hasUI) {
			ctx.ui.notify(
				decision.reason === "librarian-write"
					? "Blocked mutating git command in librarian checkout."
					: "Blocked git command in jj repo; use jj instead.",
				"warning",
			);
		}
		return { block: true, reason: buildReason(command, decision.reason) };
	});
}

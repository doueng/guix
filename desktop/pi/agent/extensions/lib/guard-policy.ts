import {
	findJjRepoRoot,
	gitWorkingDirectory,
	hasSafeShellOutputs,
	isReadOnlyGitInvocation,
	isWithin,
	librarianCheckoutRoot,
	shellInvocations,
} from "./shell-guard.ts";

export type GitGuardDecision = {
	block: boolean;
	reason?: "jj-workspace" | "librarian-write";
};

export function gitGuardDecision(command: string, cwd: string): GitGuardDecision {
	const invocations = shellInvocations(command, cwd);
	// A pipeline consumer's redirection is still part of a cached Git export.
	const readsCache = invocations.some((invocation) =>
		invocation.command === "git" && isWithin(gitWorkingDirectory(invocation), librarianCheckoutRoot()));
	if (readsCache && invocations.some((invocation) => !hasSafeShellOutputs(invocation))) {
		return { block: true, reason: "librarian-write" };
	}
	for (const invocation of invocations) {
		if (invocation.command !== "git") continue;
		const gitCwd = gitWorkingDirectory(invocation);
		if (isWithin(gitCwd, librarianCheckoutRoot())) {
			if (!isReadOnlyGitInvocation(invocation)) return { block: true, reason: "librarian-write" };
			continue;
		}
		if (findJjRepoRoot(gitCwd)) return { block: true, reason: "jj-workspace" };
	}
	return { block: false };
}

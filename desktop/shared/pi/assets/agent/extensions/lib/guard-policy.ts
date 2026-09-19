import {
	findJjRepoRoot,
	gitWorkingDirectory,
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
	for (const invocation of shellInvocations(command, cwd)) {
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

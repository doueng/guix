import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve } from "node:path";

export type ShellInvocation = {
	argv: string[];
	command: string;
	cwd: string;
};

const SEPARATORS = new Set([";", "&&", "||", "|", "\n"]);
const SHELLS = new Set(["bash", "dash", "fish", "sh", "zsh"]);
const READ_ONLY_GIT_SUBCOMMANDS = new Set([
	"blame",
	"cat-file",
	"describe",
	"diff",
	"grep",
	"log",
	"ls-files",
	"name-rev",
	"rev-parse",
	"shortlog",
	"show",
	"show-ref",
	"status",
]);

function tokenize(command: string): string[] {
	const tokens: string[] = [];
	let word = "";
	let quote: "'" | '"' | null = null;
	let escaping = false;

	const flush = () => {
		if (word) tokens.push(word);
		word = "";
	};

	for (let index = 0; index < command.length; index += 1) {
		const char = command[index]!;
		if (escaping) {
			word += char;
			escaping = false;
			continue;
		}
		if (char === "\\" && quote !== "'") {
			escaping = true;
			continue;
		}
		if (quote) {
			if (char === quote) quote = null;
			else word += char;
			continue;
		}
		if (char === "'" || char === '"') {
			quote = char;
			continue;
		}
		if (char === "\n") {
			flush();
			tokens.push("\n");
			continue;
		}
		if (char === ";" || char === "|" || char === "&") {
			flush();
			const pair = command.slice(index, index + 2);
			if (pair === "&&" || pair === "||") {
				tokens.push(pair);
				index += 1;
			} else {
				tokens.push(char);
			}
			continue;
		}
		if (/\s/u.test(char)) {
			flush();
			continue;
		}
		word += char;
	}
	if (escaping) word += "\\";
	flush();
	return tokens;
}

function splitSegments(command: string): string[][] {
	const segments: string[][] = [];
	let segment: string[] = [];
	for (const token of tokenize(command)) {
		if (SEPARATORS.has(token)) {
			if (segment.length) segments.push(segment);
			segment = [];
		} else {
			segment.push(token);
		}
	}
	if (segment.length) segments.push(segment);
	return segments;
}

function expandHome(path: string): string {
	if (path === "~") return homedir();
	if (path.startsWith("~/")) return join(homedir(), path.slice(2));
	return path;
}

function resolveFrom(cwd: string, path: string): string {
	const expanded = expandHome(path);
	return resolve(isAbsolute(expanded) ? expanded : join(cwd, expanded));
}

function unwrapCommand(argv: string[]): string[] {
	let index = 0;
	while (index < argv.length && /^[A-Za-z_][A-Za-z0-9_]*=/u.test(argv[index]!)) index += 1;
	if (basename(argv[index] ?? "") === "command") index += 1;
	if (basename(argv[index] ?? "") === "env") {
		index += 1;
		while (index < argv.length && (/^[A-Za-z_][A-Za-z0-9_]*=/u.test(argv[index]!) || argv[index] === "-i")) index += 1;
	}
	return argv.slice(index);
}

function shellScript(argv: string[]): string | null {
	if (!SHELLS.has(basename(argv[0] ?? ""))) return null;
	const option = argv.findIndex((arg) => arg === "-c" || arg === "-lc");
	return option >= 0 ? argv[option + 1] ?? null : null;
}

export function shellInvocations(command: string, initialCwd: string): ShellInvocation[] {
	const invocations: ShellInvocation[] = [];
	let cwd = resolve(initialCwd);
	for (const rawArgv of splitSegments(command)) {
		const argv = unwrapCommand(rawArgv);
		if (!argv.length) continue;
		const commandName = basename(argv[0]!);
		if ((commandName === "cd" || commandName === "pushd") && argv[1]) {
			cwd = resolveFrom(cwd, argv[1]);
			continue;
		}
		const nested = shellScript(argv);
		if (nested !== null) {
			invocations.push(...shellInvocations(nested, cwd));
			continue;
		}
		invocations.push({ argv, command: commandName, cwd });
	}
	return invocations;
}

export function gitWorkingDirectory(invocation: ShellInvocation): string {
	let cwd = invocation.cwd;
	for (let index = 1; index < invocation.argv.length; index += 1) {
		const arg = invocation.argv[index]!;
		if (arg === "-C" && invocation.argv[index + 1]) {
			cwd = resolveFrom(cwd, invocation.argv[index + 1]!);
			index += 1;
		} else if (arg.startsWith("-C") && arg.length > 2) {
			cwd = resolveFrom(cwd, arg.slice(2));
		}
	}
	return cwd;
}

export function gitSubcommand(argv: string[]): string | null {
	for (let index = 1; index < argv.length; index += 1) {
		const arg = argv[index]!;
		if (arg === "-C" || arg === "-c" || arg === "--git-dir" || arg === "--work-tree" || arg === "--namespace") {
			index += 1;
			continue;
		}
		if (arg.startsWith("-")) continue;
		return arg;
	}
	return null;
}

export function isReadOnlyGitInvocation(invocation: ShellInvocation): boolean {
	const subcommand = gitSubcommand(invocation.argv);
	return subcommand !== null && READ_ONLY_GIT_SUBCOMMANDS.has(subcommand);
}

export function findJjRepoRoot(start: string): string | null {
	let current = resolve(start);
	while (true) {
		if (existsSync(join(current, ".jj"))) return current;
		const parent = dirname(current);
		if (parent === current) return null;
		current = parent;
	}
}

export function isWithin(path: string, root: string): boolean {
	const child = relative(resolve(root), resolve(path));
	return child === "" || (!child.startsWith("..") && !isAbsolute(child));
}

export function librarianCheckoutRoot(): string {
	return join(homedir(), ".cache", "checkouts");
}

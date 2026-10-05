import { existsSync, lstatSync, realpathSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve } from "node:path";

export type ShellInvocation = {
	argv: string[];
	command: string;
	cwd: string;
	outputs: string[];
};

type ShellToken = { value: string; operator?: boolean; literal?: boolean };
const SEPARATORS = new Set([";", "&&", "||", "|", "&", "\n"]);
const REDIRECTS = new Set([">", ">>", ">|", "&>", "&>>", ">&", "<", "<&"]);
const SHELLS = new Set(["bash", "dash", "fish", "sh", "zsh"]);
const READ_ONLY_GIT_SUBCOMMANDS = new Set([
	"archive",
	"blame",
	"cat-file",
	"describe",
	"diff",
	"grep",
	"log",
	"ls-files",
	"ls-tree",
	"name-rev",
	"rev-parse",
	"shortlog",
	"show",
	"show-ref",
	"status",
]);

function tokenize(command: string): ShellToken[] {
	const tokens: ShellToken[] = [];
	let word = "";
	let hasWord = false;
	let literal = false;
	let quote: "'" | '"' | null = null;
	let escaping = false;

	const flush = () => {
		if (hasWord) tokens.push({ value: word, literal });
		word = "";
		hasWord = false;
		literal = false;
	};

	for (let index = 0; index < command.length; index += 1) {
		const char = command[index]!;
		if (escaping) {
			hasWord = true;
			if (char === "$" || char === "`") literal = true;
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
			else {
				if (quote === "'" && (char === "$" || char === "`")) literal = true;
				word += char;
			}
			continue;
		}
		if (char === "'" || char === '"') {
			hasWord = true;
			quote = char;
			continue;
		}
		if (char === "\n") {
			flush();
			tokens.push({ value: "\n", operator: true });
			continue;
		}
		if (";|&<>".includes(char)) {
			// A numeric word immediately before a redirect is its file descriptor.
			if ((char === ">" || char === "<") && /^\d+$/u.test(word)) {
				word = "";
				hasWord = false;
			}
			flush();
			const operator = ["&>>", "&&", "||", ">>", ">|", "&>", ">&", "<&"]
				.find((candidate) => command.startsWith(candidate, index)) ?? char;
			tokens.push({ value: operator, operator: true });
			index += operator.length - 1;
			continue;
		}
		if (/\s/u.test(char)) {
			flush();
			continue;
		}
		hasWord = true;
		word += char;
	}
	if (escaping) word += "\\";
	flush();
	return tokens;
}

function splitSegments(command: string): ShellToken[][] {
	const segments: ShellToken[][] = [];
	let segment: ShellToken[] = [];
	for (const token of tokenize(command)) {
		if (token.operator && SEPARATORS.has(token.value)) {
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
	const variables = new Map<string, string>();
	const expand = (word: string) => word.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)/gu,
		(match, braced: string | undefined, plain: string | undefined) => variables.get(braced ?? plain!) ?? match);
	let cwd = resolve(initialCwd);
	for (const segment of splitSegments(command)) {
		const rawArgv: string[] = [];
		const expandedArgv: string[] = [];
		const outputs: string[] = [];
		for (let index = 0; index < segment.length; index += 1) {
			const token = segment[index]!;
			if (token.operator && REDIRECTS.has(token.value)) {
				const target = segment[++index];
				const value = target?.value ?? "";
				if (token.value.includes(">") && !(token.value === ">&" && /^(\d+|-)$/u.test(value))) {
					outputs.push(target?.literal ? value : expand(value));
				}
			} else {
				rawArgv.push(token.value);
				expandedArgv.push(token.literal ? token.value : expand(token.value));
			}
		}
		// Track simple literal assignments, not arbitrary shell evaluation.
		if (rawArgv.length && rawArgv.every((arg) => /^[A-Za-z_][A-Za-z0-9_]*=/u.test(arg))) {
			for (const arg of expandedArgv) {
				const equals = arg.indexOf("=");
				variables.set(arg.slice(0, equals), expandHome(arg.slice(equals + 1)));
			}
			continue;
		}
		const argv = unwrapCommand(expandedArgv);
		if (!argv.length) continue;
		const commandName = basename(argv[0]!);
		if ((commandName === "cd" || commandName === "pushd") && argv[1]) {
			cwd = resolveFrom(cwd, argv[1]);
			continue;
		}
		const nested = shellScript(unwrapCommand(rawArgv));
		if (nested !== null) {
			invocations.push({ argv, command: commandName, cwd, outputs });
			invocations.push(...shellInvocations(nested, cwd));
			continue;
		}
		invocations.push({ argv, command: commandName, cwd, outputs });
	}
	return invocations;
}

export function gitWorkingDirectory(invocation: ShellInvocation): string {
	let cwd = invocation.cwd;
	const end = gitSubcommandIndex(invocation.argv);
	for (let index = 1; index < end; index += 1) {
		const arg = invocation.argv[index]!;
		if (["-c", "--git-dir", "--work-tree", "--namespace"].includes(arg)) {
			index += 1;
			continue;
		}
		if (arg === "-C" && invocation.argv[index + 1]) {
			cwd = resolveFrom(cwd, invocation.argv[index + 1]!);
			index += 1;
		} else if (arg.startsWith("-C") && arg.length > 2) {
			cwd = resolveFrom(cwd, arg.slice(2));
		}
	}
	return cwd;
}

function gitSubcommandIndex(argv: string[]): number {
	for (let index = 1; index < argv.length; index += 1) {
		const arg = argv[index]!;
		if (arg === "-C" || arg === "-c" || arg === "--git-dir" || arg === "--work-tree" || arg === "--namespace") {
			index += 1;
			continue;
		}
		if (arg.startsWith("-")) continue;
		return index;
	}
	return argv.length;
}

export function gitSubcommand(argv: string[]): string | null {
	return argv[gitSubcommandIndex(argv)] ?? null;
}

function isTemporaryOutput(path: string, cwd: string): boolean {
	if (!path || /[$`]/u.test(path)) return false;
	if (path === "/dev/null") return true;
	const destination = resolveFrom(cwd, path);
	// Resolve existing ancestors, including dangling symlinks, before trusting /tmp.
	let ancestor = destination;
	try {
		while (true) {
			try {
				lstatSync(ancestor);
				break;
			} catch (error) {
				if ((error as NodeJS.ErrnoException).code !== "ENOENT" || dirname(ancestor) === ancestor) return false;
				ancestor = dirname(ancestor);
			}
		}
		return isWithin(resolve(realpathSync(ancestor), relative(ancestor, destination)), realpathSync("/tmp"));
	} catch {
		return false;
	}
}

export function hasSafeShellOutputs(invocation: ShellInvocation): boolean {
	return invocation.outputs.every((path) => isTemporaryOutput(path, invocation.cwd));
}

export function isReadOnlyGitInvocation(invocation: ShellInvocation): boolean {
	if (!hasSafeShellOutputs(invocation)) return false;
	const subcommand = gitSubcommand(invocation.argv);
	const args = invocation.argv.slice(gitSubcommandIndex(invocation.argv) + 1);
	if (subcommand !== null && READ_ONLY_GIT_SUBCOMMANDS.has(subcommand)) {
		for (let index = 0; index < args.length; index += 1) {
			const arg = args[index]!;
			if (arg === "--") break;
			let output: string | undefined;
			if (arg.startsWith("--output=")) output = arg.slice("--output=".length);
			else if (arg === "--output" || (subcommand === "archive" && arg === "-o")) output = args[++index] ?? "";
			else if (subcommand === "archive" && arg.startsWith("-o")) output = arg.slice(2);
			if (output !== undefined && !isTemporaryOutput(output, gitWorkingDirectory(invocation))) return false;
		}
		return true;
	}
	if (subcommand === "worktree") return args[0] === "list";
	if (subcommand !== "tag") return false;
	return args.every((arg) =>
		arg === "-l" || arg === "--list" || arg.startsWith("--sort=") || arg.startsWith("--format=")
	);
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

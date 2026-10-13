import { extname } from "node:path";

type LexRules = {
	lineComment: string;
	blockComment?: [string, string];
	datumComment?: string;
	charQuestion: boolean;
};

const CLOJURE: LexRules = { lineComment: ";", charQuestion: false };
const SCHEME: LexRules = {
	lineComment: ";",
	blockComment: ["#|", "|#"],
	datumComment: "#;",
	charQuestion: false,
};
const ELISP: LexRules = { lineComment: ";", charQuestion: true };

const RULES_BY_EXTENSION: Record<string, LexRules> = {
	".clj": CLOJURE,
	".cljs": CLOJURE,
	".cljc": CLOJURE,
	".edn": CLOJURE,
	".bb": CLOJURE,
	".fnl": CLOJURE,
	".scm": SCHEME,
	".ss": SCHEME,
	".rkt": SCHEME,
	".lisp": SCHEME,
	".el": ELISP,
};

export const CODEMODE_RULES = CLOJURE;

export function rulesForPath(path: string): LexRules | undefined {
	return RULES_BY_EXTENSION[extname(path).toLowerCase()];
}

type Opener = { char: "(" | "[" | "{"; line: number; column: number };

type Balance =
	| { kind: "balanced" }
	| { kind: "unclosed"; stack: Opener[] }
	| { kind: "stray"; char: string; line: number; column: number };

type Scan = { balance: Balance; nestedTopLevelLines: number[] };

export type DelimiterOutcome =
	| { kind: "clean" }
	| { kind: "repaired"; content: string; note: string }
	| { kind: "unbalanced"; summary: string };

// Emacs Lisp character syntax such as ?(, ?\), and ?\C-\M-] where the last character is data.
const ELISP_CHARACTER = /\?(?:\\\^|\\[ACHMSs]-)*\\?./sy;

const CLOSER = { "(": ")", "[": "]", "{": "}" } as const;
const OPENER: Record<string, Opener["char"]> = { ")": "(", "]": "[", "}": "{" };
const MAX_TRAILING_NONBLANK_LINES = 10;
const MAX_REMOVED_CLOSERS = 256;

export function scan(content: string, rules: LexRules): Scan {
	const stack: Opener[] = [];
	const nestedTopLevelLines: number[] = [];
	let line = 1;
	let column = 1;
	let i = 0;
	const advance = (count: number) => {
		const end = Math.min(i + count, content.length);
		for (; i < end; i++) {
			if (content[i] === "\n") {
				line++;
				column = 1;
			} else column++;
		}
	};
	while (i < content.length) {
		const c = content[i];
		if (rules.datumComment && content.startsWith(rules.datumComment, i)) {
			advance(rules.datumComment.length);
		} else if (content.startsWith(rules.lineComment, i)) {
			const end = content.indexOf("\n", i);
			advance((end === -1 ? content.length : end) - i);
		} else if (rules.blockComment && content.startsWith(rules.blockComment[0], i)) {
			const [open, close] = rules.blockComment;
			advance(open.length);
			let depth = 1;
			while (i < content.length && depth > 0) {
				if (content.startsWith(open, i)) {
					depth++;
					advance(open.length);
				} else if (content.startsWith(close, i)) {
					depth--;
					advance(close.length);
				} else advance(1);
			}
		} else if (c === '"') {
			advance(1);
			while (i < content.length && content[i] !== '"') advance(content[i] === "\\" ? 2 : 1);
			advance(1);
		} else if (c === "\\") {
			advance(2);
		} else if (rules.charQuestion && c === "?" && /^$|[\s([{'`,]/.test(content[i - 1] ?? "")) {
			ELISP_CHARACTER.lastIndex = i;
			advance(ELISP_CHARACTER.exec(content)?.[0].length ?? 1);
		} else if (c === "(" || c === "[" || c === "{") {
			if (column === 1 && stack.length > 0) nestedTopLevelLines.push(line);
			stack.push({ char: c, line, column });
			advance(1);
		} else if (c === ")" || c === "]" || c === "}") {
			if (stack.at(-1)?.char !== OPENER[c])
				return { balance: { kind: "stray", char: c, line, column }, nestedTopLevelLines };
			stack.pop();
			advance(1);
		} else advance(1);
	}
	return {
		balance: stack.length ? { kind: "unclosed", stack } : { kind: "balanced" },
		nestedTopLevelLines,
	};
}

function summary(balance: Exclude<Balance, { kind: "balanced" }>) {
	if (balance.kind === "stray")
		return `Delimiter imbalance: unexpected \`${balance.char}\` at line ${balance.line}, column ${balance.column} with no matching opener. Remove the extra closer or add the missing opener before it.`;
	const { stack } = balance;
	const inner = stack.at(-1) as Opener;
	const outer = stack[0];
	const rest =
		stack.length > 1
			? ` ${stack.length - 1} more remain open; the outermost is the \`${outer.char}\` at line ${outer.line}, column ${outer.column}.`
			: "";
	return `Delimiter imbalance: ${stack.length} unclosed. The innermost is the \`${inner.char}\` opened at line ${inner.line}, column ${inner.column}; close it with \`${CLOSER[inner.char]}\`.${rest}`;
}

function closeTrailingTruncation(content: string, { balance, nestedTopLevelLines }: Scan) {
	if (balance.kind !== "unclosed") return undefined;
	const outer = balance.stack[0];
	const trailing = content
		.split("\n")
		.slice(outer.line)
		.filter((line) => line.trim()).length;
	if (trailing > MAX_TRAILING_NONBLANK_LINES) return undefined;
	// A form starting in column 1 inside the open region is a top-level form
	// that closing at EOF would silently re-parent.
	if (nestedTopLevelLines.some((line) => line >= outer.line)) return undefined;
	const closers = balance.stack
		.toReversed()
		.map((opener) => CLOSER[opener.char])
		.join("");
	return {
		content: content + closers,
		note: `appended \`${closers}\` to close the \`${outer.char}\` opened at line ${outer.line}, column ${outer.column}.`,
	};
}

function removeTrailingOverclose(content: string, rules: LexRules) {
	let current = content;
	let removed = 0;
	for (;;) {
		const { balance } = scan(current, rules);
		if (balance.kind === "balanced") break;
		if (balance.kind === "unclosed" || removed >= MAX_REMOVED_CLOSERS) return undefined;
		const end = current.trimEnd().length;
		if (!")]}".includes(current[end - 1] ?? "x")) return undefined;
		current = current.slice(0, end - 1) + current.slice(end);
		removed++;
	}
	return removed
		? { content: current, note: `removed ${removed} extra trailing closing delimiter(s).` }
		: undefined;
}

export function repairDelimiters(content: string, rules: LexRules): DelimiterOutcome {
	const result = scan(content, rules);
	if (result.balance.kind === "balanced") return { kind: "clean" };
	const repair =
		closeTrailingTruncation(content, result) ??
		(result.balance.kind === "stray" ? removeTrailingOverclose(content, rules) : undefined);
	if (repair && scan(repair.content, rules).balance.kind === "balanced")
		return {
			kind: "repaired",
			content: repair.content,
			note: `[auto-repair] Unbalanced delimiters: ${repair.note} If that placement is wrong, resend the corrected text.`,
		};
	return { kind: "unbalanced", summary: summary(result.balance) };
}

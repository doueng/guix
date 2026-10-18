import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AgentToolResult } from "@earendil-works/pi-coding-agent";

export const DEFAULT_OUTPUT_TOKENS = 10_000;
export const EXECUTOR_OUTPUT_TOKENS = 2_500_000;
const CHARS_PER_TOKEN = 4;
const MIN_KEPT_CHARS = 200;

const PROTECTED = [
	/^Script error:\n/,
	/^SCI transaction failed:/,
	/^\[Image /,
	/^Note: models\.generateImages/,
];
const isProtected = (text: string) => PROTECTED.some((pattern) => pattern.test(text));

export function condenseFailure(text: string) {
	return text
		.split("\n")
		.filter((line) => !/^\s+at .*\(?codemode\.js:\d+:\d+\)?$/.test(line))
		.map((line) => {
			const match = /^(Tool calls made before the failure \(they are not undone\): )(.*)$/.exec(
				line,
			);
			if (!match) return line;
			const counts = new Map<string, number>();
			for (const call of match[2].split(", ")) counts.set(call, (counts.get(call) ?? 0) + 1);
			return (
				match[1] +
				[...counts].map(([call, count]) => (count > 1 ? `${call} x${count}` : call)).join(", ")
			);
		})
		.join("\n");
}

function fairCap(sizes: number[], budget: number) {
	const sorted = [...sizes].sort((a, b) => a - b);
	let remaining = budget;
	for (let i = 0; i < sorted.length; i++) {
		const share = Math.floor(remaining / (sorted.length - i));
		if (sorted[i] > share) return Math.max(share, MIN_KEPT_CHARS);
		remaining -= sorted[i];
	}
	return Number.POSITIVE_INFINITY;
}

async function spill(text: string) {
	const directory = await mkdtemp(join(tmpdir(), "pi-sci-output-"));
	const path = join(directory, "output.txt");
	await writeFile(path, text, { mode: 0o600 });
	return path;
}

// Pi's executor joins adjacent text items, marks each text() block with
// `==> text k/N <==`, and appends console output and the failure to the same
// item. Splitting at those boundaries recovers the blocks; joining the parts
// with a newline restores the item exactly.
const BOUNDARY =
	/\n(?===> text \d+\/\d+ <==\n|<console_output>\n|Script error:\n|Note: models\.generateImages)/;
const MARKER = /^==> text \d+\/\d+ <==\n/;

export async function budgetOutput<T extends AgentToolResult<unknown>>(
	result: T,
	maxTokens: number,
): Promise<T> {
	if (!result.content.length) return result;
	const [header, ...items] = result.content;
	const split = items.map((item) =>
		item.type === "text"
			? item.text.split(BOUNDARY).map((part) => {
					const marker = MARKER.exec(part)?.[0] ?? "";
					const body = part.slice(marker.length);
					return {
						marker,
						body: body.startsWith("Script error:\n") ? condenseFailure(body) : body,
					};
				})
			: undefined,
	);
	const parts = split.flatMap((item) => item ?? []);
	const fixed = parts.filter(({ body }) => isProtected(body));
	const flexible = parts.filter(({ body }) => !isProtected(body));
	const budget = Math.max(
		0,
		maxTokens * CHARS_PER_TOKEN - fixed.reduce((sum, { body }) => sum + body.length, 0),
	);
	const cap = fairCap(
		flexible.map(({ body }) => body.length),
		budget,
	);
	let path: string | undefined;
	let failure: string | undefined;
	const lines = new Map<(typeof parts)[number], [number, number]>();
	if (Number.isFinite(cap)) {
		try {
			path = await spill(parts.map(({ marker, body }) => marker + body).join("\n"));
		} catch (error) {
			failure = error instanceof Error ? error.message : String(error);
		}
		let line = 1;
		for (const part of parts) {
			const first = line + (part.marker ? 1 : 0);
			const last = first + part.body.split("\n").length - 1;
			lines.set(part, [first, last]);
			line = last + 1;
		}
	}
	const clip = (part: (typeof parts)[number]) => {
		if (!flexible.includes(part) || part.body.length <= cap) return part.body;
		const head = Math.ceil(cap * 0.6);
		const tail = cap - head;
		const [first, last] = lines.get(part) ?? [0, 0];
		const where = path
			? `full text: (fs/read ${JSON.stringify(path)} {:start_line ${first} :end_line ${last}})`
			: `full text could not be saved: ${failure}`;
		return `${part.body.slice(0, head)}\n...[clipped ${part.body.length - cap} of ${part.body.length} chars; ${where}]...\n${tail > 0 ? part.body.slice(-tail) : ""}`;
	};
	const content = items.map((item, index) => {
		const itemParts = split[index];
		return itemParts && item.type === "text"
			? { ...item, text: itemParts.map((part) => part.marker + clip(part)).join("\n") }
			: item;
	});
	return {
		...result,
		content: [header, ...content],
		details: { ...(result.details as object), ...(path ? { fullOutputPath: path } : {}) },
	};
}

// Pi's codemode executor reports progress after every nested call, and each
// report carries the whole call list, so a script with n calls sends O(n²)
// bytes to the UI. Deliver the first report, then at most one per interval,
// always the latest. stop() drops a pending report once the call returns.
export function throttleUpdates<T>(update: ((value: T) => void) | undefined, intervalMs = 250) {
	if (!update) return { update, stop() {} };
	let last = 0;
	let pending: { value: T } | undefined;
	let timer: ReturnType<typeof setTimeout> | undefined;
	const flush = () => {
		timer = undefined;
		if (!pending) return;
		last = Date.now();
		const { value } = pending;
		pending = undefined;
		update(value);
	};
	return {
		update(value: T) {
			pending = { value };
			const wait = last + intervalMs - Date.now();
			if (wait <= 0 && !timer) flush();
			else timer ??= setTimeout(flush, Math.max(0, wait));
		},
		stop() {
			if (timer) clearTimeout(timer);
			timer = undefined;
			pending = undefined;
		},
	};
}

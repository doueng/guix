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

export async function budgetOutput<T extends AgentToolResult<unknown>>(
	result: T,
	maxTokens: number,
): Promise<T> {
	if (!result.content.length) return result;
	const [header, ...items] = result.content.map((item) =>
		item.type === "text" && item.text.startsWith("Script error:\n")
			? { ...item, text: condenseFailure(item.text) }
			: item,
	);
	const texts = items.flatMap((item, index) =>
		item.type === "text" ? [{ index, text: item.text }] : [],
	);
	const fixed = texts.filter(({ text }) => isProtected(text));
	const flexible = texts.filter(({ text }) => !isProtected(text));
	const budget = Math.max(
		0,
		maxTokens * CHARS_PER_TOKEN - fixed.reduce((sum, { text }) => sum + text.length, 0),
	);
	const cap = fairCap(
		flexible.map(({ text }) => text.length),
		budget,
	);
	if (!Number.isFinite(cap)) return { ...result, content: [header, ...items] };
	const combined = texts.map(({ text }) => text).join("\n");
	let path: string | undefined;
	let failure: string | undefined;
	try {
		path = await spill(combined);
	} catch (error) {
		failure = error instanceof Error ? error.message : String(error);
	}
	let line = 1;
	const lines = new Map<number, [number, number]>();
	for (const { index, text } of texts) {
		const count = text.split("\n").length;
		lines.set(index, [line, line + count - 1]);
		line += count;
	}
	const clipped = items.map((item, index) => {
		const entry = flexible.find((candidate) => candidate.index === index);
		if (!entry || entry.text.length <= cap || item.type !== "text") return item;
		const head = Math.ceil(cap * 0.6);
		const tail = cap - head;
		const [first, last] = lines.get(index) ?? [0, 0];
		const where = path
			? `full text: (fs/read ${JSON.stringify(path)} {:start_line ${first} :end_line ${last}})`
			: `full text could not be saved: ${failure}`;
		return {
			...item,
			text: `${entry.text.slice(0, head)}\n...[clipped ${entry.text.length - cap} of ${entry.text.length} chars; ${where}]...\n${tail > 0 ? entry.text.slice(-tail) : ""}`,
		};
	});
	return {
		...result,
		content: [header, ...clipped],
		details: { ...(result.details as object), ...(path ? { fullOutputPath: path } : {}) },
	};
}

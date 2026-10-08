#!/usr/bin/env node
import { createReadStream, readdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";

const CODE = {
	bash: /tools\/bash/,
	tools_read: /tools\/read\b/,
	catalog: /catalog\/(search|describe)/,
};
const RESULT = {
	escaped_bash_map: /\{:output "/,
	budget_truncated: /Warning: truncated output/,
	clipped_block: /\[clipped \d+ of \d+ chars/,
	parse_error: /Unmatched delimiter|EOF while reading/,
	validation_error: /Validation failed for tool|does not accept :|Accepted keys:/,
	hint: /\nHint: /,
};

function latest(count) {
	const root = join(homedir(), ".pi/agent/sessions");
	return readdirSync(root)
		.flatMap((dir) => readdirSync(join(root, dir)).map((file) => join(root, dir, file)))
		.filter((path) => path.endsWith(".jsonl"))
		.sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
		.slice(0, count);
}

async function measure(path) {
	const counts = { codemode: 0, errors: 0 };
	for (const key of [...Object.keys(CODE), ...Object.keys(RESULT)]) counts[key] = 0;
	for await (const line of createInterface({ input: createReadStream(path) })) {
		if (!line.trim()) continue;
		const { type, message } = JSON.parse(line);
		if (type !== "message" || !Array.isArray(message?.content)) continue;
		if (message.role === "assistant")
			for (const part of message.content) {
				if (part.type !== "toolCall" || part.name !== "codemode") continue;
				counts.codemode++;
				const code = String(part.arguments?.code ?? "");
				for (const [key, pattern] of Object.entries(CODE)) if (pattern.test(code)) counts[key]++;
			}
		if (message.role === "toolResult" && message.toolName === "codemode") {
			const text = message.content.map((part) => part.text ?? "").join("\n");
			if (message.isError) counts.errors++;
			for (const [key, pattern] of Object.entries(RESULT)) if (pattern.test(text)) counts[key]++;
		}
	}
	return counts;
}

const args = process.argv.slice(2);
const files = args.length && !/^\d+$/.test(args[0]) ? args : latest(Number(args[0] ?? 7));
const rows = [];
for (const file of files)
	rows.push({ session: file.split("/").at(-1).slice(0, 19), ...(await measure(file)) });
const total = Object.fromEntries(
	Object.keys(rows[0] ?? {})
		.filter((key) => key !== "session")
		.map((key) => [key, rows.reduce((sum, row) => sum + row[key], 0)]),
);
console.table([...rows, { session: "total", ...total }]);
if (total.codemode)
	console.log(`bash share ${((100 * total.bash) / total.codemode).toFixed(1)}% of codemode calls`);

#!/usr/bin/env node
import { createReadStream, readdirSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { CODEMODE_RULES, repairDelimiters, rulesForPath, scan } from "../src/delimiters.ts";

const READER_ERROR =
	/^Script error:\nError: (Unmatched delimiter|EOF while reading|Unexpected EOF)/m;
const CLOJURE_START = /^\s*[(;]/;
const squash = (code) => code.replace(/\s+/g, "");

function sessions(count) {
	const root = join(homedir(), ".pi/agent/sessions");
	return readdirSync(root)
		.flatMap((dir) => readdirSync(join(root, dir)).map((file) => join(root, dir, file)))
		.filter((path) => path.endsWith(".jsonl"))
		.sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
		.slice(0, count);
}

async function replay(path, tally, examples) {
	const calls = [];
	const failed = new Map();
	const succeeded = new Set();
	for await (const line of createInterface({ input: createReadStream(path) })) {
		if (!line.trim()) continue;
		const { type, message } = JSON.parse(line);
		if (type !== "message" || !Array.isArray(message?.content)) continue;
		if (message.role === "assistant")
			for (const part of message.content)
				if (
					part.type === "toolCall" &&
					part.name === "codemode" &&
					CLOJURE_START.test(String(part.arguments?.code))
				)
					calls.push({ id: part.id, code: String(part.arguments?.code ?? "") });
		if (message.role === "toolResult" && message.toolName === "codemode") {
			const text = message.content.map((part) => part.text ?? "").join("\n");
			if (READER_ERROR.test(text)) failed.set(message.toolCallId, text);
			else if (!message.isError) succeeded.add(message.toolCallId);
		}
	}
	const record = (verdict, example) => {
		tally[verdict] = (tally[verdict] ?? 0) + 1;
		if (example) examples[verdict] = [...(examples[verdict] ?? []), { path, ...example }];
	};
	calls.forEach((call, index) => {
		if (!succeeded.has(call.id) && !failed.has(call.id)) return;
		const outcome = repairDelimiters(call.code, CODEMODE_RULES);
		const example = { id: call.id, note: outcome.note ?? outcome.summary, code: call.code };
		if (succeeded.has(call.id)) {
			if (outcome.kind === "clean") record("succeeded, scanner clean");
			else record("succeeded, scanner FALSE POSITIVE", example);
			return;
		}
		const next = calls[index + 1]?.code;
		let verdict = outcome.kind;
		if (outcome.kind === "repaired")
			verdict =
				next && squash(next) === squash(outcome.content)
					? "repaired, retry identical"
					: "repaired, retry differs";
		record(verdict, { ...example, error: failed.get(call.id).slice(0, 160), retry: next });
	});
}

function scanFiles(paths) {
	const tally = {};
	const flagged = [];
	for (const path of paths) {
		const rules = rulesForPath(path);
		if (!rules || !statSync(path).isFile()) continue;
		const content = readFileSync(path, "utf8");
		const outcome = repairDelimiters(content, rules);
		tally[outcome.kind] = (tally[outcome.kind] ?? 0) + 1;
		if (outcome.kind !== "clean")
			flagged.push({
				path,
				note: outcome.note ?? outcome.summary,
				firstNestedTopLevel: scan(content, rules).nestedTopLevelLines[0],
			});
	}
	return { files: paths.length, tally, flagged };
}

const [mode, ...args] = process.argv.slice(2);
if (mode === "files") console.log(JSON.stringify(scanFiles(args), null, 2));
else {
	const tally = {};
	const examples = {};
	const files = sessions(Number(mode ?? 1000));
	for (const file of files) await replay(file, tally, examples);
	console.log(JSON.stringify({ sessions: files.length, tally, examples }, null, 2));
}

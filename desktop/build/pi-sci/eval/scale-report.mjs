#!/usr/bin/env node
// Per-size cost and wall-time ratios of each profile against vanilla for the
// scale suite, paired by task and attempt. --before ISO keeps only SCI trials
// that started before that time, for runs that straddle a rebuild.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { listCost, priceFor, readEvents, summarize } from "./metrics.mjs";

const [dir, ...rest] = process.argv.slice(2);
const before = rest[0] === "--before" ? Date.parse(rest[1]) : null;
const price = priceFor(JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8")).model);
const rows = readFileSync(join(dir, "results.jsonl"), "utf8")
	.split("\n")
	.filter(Boolean)
	.map((line) => JSON.parse(line))
	.filter((row) => row.task.startsWith("scale-"))
	.map((row) => {
		const events = readEvents(
			join(dir, "trials", `${row.task}__a${row.attempt}__${row.profile}`, "events.jsonl"),
		);
		const m = summarize(events);
		const started = Date.parse(events.find((e) => e.type === "session")?.timestamp ?? "");
		return { ...row, ...m, list: listCost(m.usage, price), started };
	});
const median = (xs) => {
	const s = [...xs].sort((a, b) => a - b);
	return s.length % 2 ? s[(s.length - 1) / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
};
const key = (row) => `${row.task}#${row.attempt}`;
const base = new Map(rows.filter((r) => r.profile === "vanilla").map((r) => [key(r), r]));
const out = [];
for (const task of [...new Set(rows.map((r) => r.task))]) {
	const vanilla = rows.filter((r) => r.task === task && r.profile === "vanilla");
	const entry = {
		task,
		"vanilla $": median(vanilla.map((r) => r.list)).toFixed(4),
		"vanilla s": median(vanilla.map((r) => r.wallSeconds)).toFixed(0),
	};
	for (const profile of ["sci-bare", "sci", "sci-nopy"]) {
		const pairs = rows
			.filter((r) => r.task === task && r.profile === profile && base.has(key(r)))
			.filter((r) => before === null || r.started < before)
			.map((r) => [base.get(key(r)), r]);
		if (!pairs.length) continue;
		entry[`${profile} $×`] = median(pairs.map(([b, r]) => r.list / b.list)).toFixed(2);
		entry[`${profile} wall×`] = median(
			pairs.map(([b, r]) => r.wallSeconds / b.wallSeconds),
		).toFixed(2);
		entry[`${profile} pass`] = `${pairs.filter(([, r]) => r.passed).length}/${pairs.length}`;
	}
	out.push(entry);
}
console.table(out);

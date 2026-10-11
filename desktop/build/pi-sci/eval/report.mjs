#!/usr/bin/env node
// Summarize a run directory from eval/run.mjs: per-profile outcomes and costs,
// per-task pass counts, and paired deltas against a baseline profile. Grades
// come from results.jsonl; metrics are recomputed from each trial's events.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { readEvents, summarize } from "./metrics.mjs";

const [dir, baseline = "vanilla"] = process.argv.slice(2);
const rows = readFileSync(join(dir, "results.jsonl"), "utf8")
	.split("\n")
	.filter(Boolean)
	.map((line) => JSON.parse(line))
	.map((row) => ({
		...row,
		...summarize(
			readEvents(
				join(dir, "trials", `${row.task}__a${row.attempt}__${row.profile}`, "events.jsonl"),
			),
		),
	}));
const profiles = [...new Set(rows.map((row) => row.profile))];
const tasks = [...new Set(rows.map((row) => row.task))];

const median = (xs) => {
	const s = [...xs].sort((a, b) => a - b);
	return s.length
		? s.length % 2
			? s[(s.length - 1) / 2]
			: (s[s.length / 2 - 1] + s[s.length / 2]) / 2
		: NaN;
};
const sum = (xs) => xs.reduce((a, b) => a + b, 0);
const pct = (x) => `${(100 * x).toFixed(0)}%`;
const range = (xs) => `${Math.min(...xs)}–${Math.max(...xs)}`;

const table = profiles.map((profile) => {
	const rs = rows.filter((row) => row.profile === profile);
	const passed = rs.filter((row) => row.passed);
	const cost = sum(rs.map((row) => row.cost));
	return {
		profile,
		n: rs.length,
		pass: pct(passed.length / rs.length),
		"pass@k tasks": `${tasks.filter((task) => rs.some((row) => row.task === task && row.passed)).length}/${tasks.length}`,
		"wall s p50": median(rs.map((row) => row.wallSeconds)).toFixed(1),
		"turns p50": median(rs.map((row) => row.turns)),
		"calls p50": median(rs.map((row) => row.outerCalls)),
		"inner p50": median(rs.map((row) => row.innerCalls)),
		"call err": pct(
			sum(rs.map((row) => row.outerErrors)) / Math.max(1, sum(rs.map((row) => row.outerCalls))),
		),
		"inner err": pct(
			sum(rs.map((row) => row.innerErrors)) / Math.max(1, sum(rs.map((row) => row.innerCalls))),
		),
		"tok p50": median(rs.map((row) => row.usage.totalTokens)),
		"tok range": range(rs.map((row) => row.usage.totalTokens)),
		"out tok p50": median(rs.map((row) => row.usage.output)),
		"prompt tok p50": median(rs.map((row) => row.promptTokens)),
		"result chars p50": median(rs.map((row) => row.resultChars)),
		crashes: rs.filter((row) => row.exit !== 0 && !row.timedOut).length,
		"$ total": cost.toFixed(4),
		"passes per $": (passed.length / cost).toFixed(0),
		timeouts: rs.filter((row) => row.timedOut).length,
	};
});
console.table(table);

const perTask = Object.fromEntries(
	tasks.map((task) => [
		task,
		Object.fromEntries(
			profiles.map((profile) => {
				const rs = rows.filter((row) => row.task === task && row.profile === profile);
				return [
					profile,
					`${rs.filter((row) => row.passed).length}/${rs.length} ${median(rs.map((row) => row.usage.totalTokens))}t ${median(rs.map((row) => row.wallSeconds)).toFixed(0)}s`,
				];
			}),
		),
	]),
);
console.log("per task: passes/trials, median tokens, median wall");
console.table(perTask);

// Paired by (task, attempt): the same fixture ran under both profiles.
if (profiles.includes(baseline)) {
	const key = (row) => `${row.task}#${row.attempt}`;
	const base = new Map(
		rows.filter((row) => row.profile === baseline).map((row) => [key(row), row]),
	);
	const paired = profiles
		.filter((profile) => profile !== baseline)
		.map((profile) => {
			const pairs = rows
				.filter((row) => row.profile === profile && base.has(key(row)))
				.map((row) => [base.get(key(row)), row]);
			const ratio = (f) => median(pairs.map(([b, r]) => f(r) / Math.max(1e-9, f(b)))).toFixed(2);
			return {
				profile,
				pairs: pairs.length,
				"only it passed": pairs.filter(([b, r]) => r.passed && !b.passed).length,
				"only base passed": pairs.filter(([b, r]) => b.passed && !r.passed).length,
				"tokens ×": ratio((r) => r.usage.totalTokens),
				"cost ×": ratio((r) => r.cost),
				"wall ×": ratio((r) => r.wallSeconds),
				"turns ×": ratio((r) => r.turns),
				"fewer tokens": `${pairs.filter(([b, r]) => r.usage.totalTokens < b.usage.totalTokens).length}/${pairs.length}`,
			};
		});
	console.log(`paired against ${baseline}: median per-pair ratios (<1 means cheaper or faster)`);
	console.table(paired);
}

#!/usr/bin/env node
// Summarize a run directory from eval/run.mjs: per-profile outcomes and costs,
// per-task pass counts, paired deltas against a baseline profile, and per-prompt
// cumulative cost for multi-prompt tasks. Grades come from results.jsonl;
// metrics are recomputed from each trial's events. REPORT_FORMAT=json prints
// the same tables as JSON.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { listCost, priceFor, promptSegments, readEvents, summarize } from "./metrics.mjs";

const [dir, baseline = "vanilla", only] = process.argv.slice(2);
const manifest = JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8"));
const price = priceFor(manifest.model);
const all = readFileSync(join(dir, "results.jsonl"), "utf8")
	.split("\n")
	.filter(Boolean)
	.map((line) => JSON.parse(line))
	.filter((row) => !only || only.split(",").includes(row.task))
	.map((row) => {
		const events = readEvents(
			join(dir, "trials", `${row.task}__a${row.attempt}__${row.profile}`, "events.jsonl"),
		);
		const metrics = summarize(events);
		const segments = promptSegments(events);
		// A prompt resent after a rate limit spans several segments.
		const grouped = (row.invocations ?? segments.map(() => 1)).map((n, i, counts) => {
			const start = counts.slice(0, i).reduce((a, b) => a + b, 0);
			return segments.slice(start, start + n).flat();
		});
		const prompts = grouped.map((segment) => {
			const m = summarize(segment);
			return {
				tokens: m.usage.totalTokens,
				listCost: listCost(m.usage, price),
				turns: m.turns,
				failed: m.stopReason === "error",
			};
		});
		return { ...row, ...metrics, listCost: listCost(metrics.usage, price), prompts };
	});
// A trial in which any prompt ended on a provider error says nothing about the
// harness, so it leaves every table and is only counted.
const broken = (row) => row.prompts.some((prompt) => prompt.failed);
const invalid = all.filter(broken);
const rows = all.filter((row) => !broken(row));
const profiles = [...new Set(all.map((row) => row.profile))];
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
const share = (part, whole) => pct(part / Math.max(1, whole));

const table = profiles.map((profile) => {
	const rs = rows.filter((row) => row.profile === profile);
	const passed = rs.filter((row) => row.passed);
	const list = sum(rs.map((row) => row.listCost));
	return {
		profile,
		n: rs.length,
		pass: pct(passed.length / rs.length),
		"pass@k tasks": `${tasks.filter((task) => rs.some((row) => row.task === task && row.passed)).length}/${tasks.length}`,
		"wall s p50": Number(median(rs.map((row) => row.wallSeconds)).toFixed(1)),
		"turns p50": median(rs.map((row) => row.turns)),
		"calls p50": median(rs.map((row) => row.outerCalls)),
		"inner p50": median(rs.map((row) => row.innerCalls)),
		"call err": share(sum(rs.map((row) => row.outerErrors)), sum(rs.map((row) => row.outerCalls))),
		"inner err": share(sum(rs.map((row) => row.innerErrors)), sum(rs.map((row) => row.innerCalls))),
		"tok p50": median(rs.map((row) => row.usage.totalTokens)),
		"cached share": share(
			sum(rs.map((row) => row.usage.cacheRead)),
			sum(rs.map((row) => row.usage.totalTokens)),
		),
		"prompt tok p50": median(rs.map((row) => row.promptTokens)),
		"result chars p50": median(rs.map((row) => row.resultChars)),
		"list $ total": Number(list.toFixed(4)),
		"passes per list $": Number((passed.length / list).toFixed(1)),
		crashes: rs.filter((row) => row.exit !== 0 && !row.timedOut).length,
		"invalid (provider error)": invalid.filter((row) => row.profile === profile).length,
		timeouts: rs.filter((row) => row.timedOut).length,
	};
});

const perTask = Object.fromEntries(
	tasks.map((task) => [
		task,
		Object.fromEntries(
			profiles.map((profile) => {
				const rs = rows.filter((row) => row.task === task && row.profile === profile);
				return [
					profile,
					`${rs.filter((row) => row.passed).length}/${rs.length} ${median(rs.map((row) => row.usage.totalTokens))}t $${median(rs.map((row) => row.listCost)).toFixed(3)} ${median(rs.map((row) => row.wallSeconds)).toFixed(0)}s`,
				];
			}),
		),
	]),
);

// Paired by (task, attempt): the same fixture ran under both profiles.
const key = (row) => `${row.task}#${row.attempt}`;
const base = new Map(rows.filter((row) => row.profile === baseline).map((row) => [key(row), row]));
const paired = profiles
	.filter((profile) => profile !== baseline && base.size)
	.map((profile) => {
		const pairs = rows
			.filter((row) => row.profile === profile && base.has(key(row)))
			.map((row) => [base.get(key(row)), row]);
		const ratio = (f) =>
			Number(median(pairs.map(([b, r]) => f(r) / Math.max(1e-9, f(b)))).toFixed(2));
		return {
			profile,
			pairs: pairs.length,
			"only it passed": pairs.filter(([b, r]) => r.passed && !b.passed).length,
			"only base passed": pairs.filter(([b, r]) => b.passed && !r.passed).length,
			"tokens ×": ratio((r) => r.usage.totalTokens),
			"list cost ×": ratio((r) => r.listCost),
			"wall ×": ratio((r) => r.wallSeconds),
			"turns ×": ratio((r) => r.turns),
			cheaper: `${pairs.filter(([b, r]) => r.listCost < b.listCost).length}/${pairs.length}`,
		};
	});

// Cumulative median list cost after each prompt of a multi-prompt task.
const perPrompt = Object.fromEntries(
	tasks
		.filter((task) => rows.some((row) => row.task === task && row.prompts.length > 1))
		.map((task) => {
			const rs = rows.filter((row) => row.task === task);
			const count = Math.max(...rs.map((row) => row.prompts.length));
			return [
				task,
				Array.from({ length: count }, (_, i) =>
					Object.fromEntries([
						["prompt", i + 1],
						...profiles.map((profile) => {
							const cumulative = rs
								.filter((row) => row.profile === profile && row.prompts.length > i)
								.map((row) => sum(row.prompts.slice(0, i + 1).map((p) => p.listCost)));
							return [profile, Number(median(cumulative).toFixed(4))];
						}),
					]),
				),
			];
		}),
);

if (process.env.REPORT_FORMAT === "json") {
	console.log(
		JSON.stringify({ model: manifest.model, table, perTask, paired, perPrompt }, null, 2),
	);
} else {
	console.log(`model ${manifest.model}; list prices ${JSON.stringify(price)}`);
	console.table(table);
	console.log("per task: passes/trials, median tokens, median list $, median wall");
	console.table(perTask);
	if (paired.length) {
		console.log(`paired against ${baseline}: median per-pair ratios (<1 means cheaper or faster)`);
		console.table(paired);
	}
	for (const [task, steps] of Object.entries(perPrompt)) {
		console.log(`${task}: cumulative median list $ after each prompt`);
		console.table(steps);
	}
}

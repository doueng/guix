#!/usr/bin/env node
// Re-run each trial's grader against its kept workspace and rewrite
// results.jsonl, keeping the old file as results.jsonl.bak. Use after fixing
// a grader; trials are not rerun.
import { copyFileSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { TASKS } from "./registry.mjs";

for (const dir of process.argv.slice(2)) {
	const path = join(dir, "results.jsonl");
	const rows = readFileSync(path, "utf8")
		.split("\n")
		.filter(Boolean)
		.map((line) => JSON.parse(line));
	let changed = 0;
	for (const row of rows) {
		const task = TASKS.find((t) => t.id === row.task);
		if (!task) continue;
		const ws = join(dir, "trials", `${row.task}__a${row.attempt}__${row.profile}`, "ws");
		const grade = await task.validate(ws);
		if (grade.passed !== row.passed) {
			changed++;
			console.log(`${row.task} a${row.attempt} ${row.profile}: ${row.passed} -> ${grade.passed}`);
		}
		Object.assign(row, { passed: grade.passed, detail: grade.detail });
	}
	copyFileSync(path, `${path}.bak`);
	writeFileSync(path, rows.map((row) => `${JSON.stringify(row)}\n`).join(""));
	console.log(`${dir}: ${changed} grades changed`);
}

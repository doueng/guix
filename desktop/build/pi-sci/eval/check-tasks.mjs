#!/usr/bin/env node
// Prove each grader rejects the untouched fixture and accepts a reference
// solution, so a pass in a live run means the task was done.
import { execFileSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { TASKS } from "./registry.mjs";

const put = (ws, text) => writeFile(join(ws, "answer.txt"), `${text}\n`);
const edit = async (ws, path, from, to) =>
	writeFile(join(ws, path), (await readFile(join(ws, path), "utf8")).replaceAll(from, to));

function csvRow(line) {
	return [...line.matchAll(/("([^"]*)"|[^,]*)(,|$)/g)].slice(0, -1).map((m) => m[2] ?? m[1]);
}

async function revenue(ws, input, output) {
	const totals = {};
	const [, ...rows] = (await readFile(join(ws, input), "utf8")).trim().split("\n");
	for (const row of rows) {
		const [, , region, amount] = csvRow(row);
		const key = region.trim().toLowerCase();
		totals[key] = (totals[key] ?? 0) + Math.round(Number(amount.replace(/[$,]/g, "")) * 100);
	}
	const text = Object.keys(totals)
		.sort()
		.map((key) => `${key} ${(totals[key] / 100).toFixed(2)}`)
		.join("\n");
	await writeFile(join(ws, output), `${text}\n`);
}

const SOLUTIONS = {
	"discover-executable": (ws) =>
		put(ws, execFileSync("sh", ["-c", "command -v jq"]).toString().trim()),
	"navigate-service": (ws) => put(ws, "system/services/remote.scm:2222"),
	"fix-module": (ws) =>
		edit(
			ws,
			"src/version.js",
			'const x = left[i] ?? "0";\n\t\tconst y = right[i] ?? "0";',
			"const x = Number(left[i] ?? 0);\n\t\tconst y = Number(right[i] ?? 0);",
		),
	"rename-function": async (ws) => {
		for (const path of [
			"src/config.js",
			"src/app.js",
			"src/cli.js",
			"test/config.test.js",
			"README.md",
		])
			await edit(ws, path, "parseConfig", "loadConfig");
	},
	"structured-analysis": (ws) => put(ws, "bat\nfd\nhyperfine\nripgrep\ntealdeer\nzoxide"),
	"large-output": async (ws) =>
		put(
			ws,
			(await readFile(join(ws, "build.log"), "utf8"))
				.split("\n")
				.filter((line) => line.startsWith("error:"))
				.join("\n"),
		),
	"failure-recovery": (ws) => put(ws, "37"),
	"parallel-checks": (ws) => put(ws, "types.sh"),
	"session-reuse": async (ws) => {
		await revenue(ws, "data/2026-09.csv", "sept.txt");
		await revenue(ws, "data/2026-10.csv", "oct.txt");
	},
	"fanout-checks": async (ws) => {
		const failed = [];
		for (const name of (await readdir(join(ws, "checks"))).sort())
			try {
				execFileSync(join(ws, "checks", name), { stdio: "ignore" });
			} catch {
				failed.push(name);
			}
		await put(ws, failed.join("\n"));
	},
	"fanout-services": async (ws) => {
		const services = [];
		for (const name of await readdir(join(ws, "services")))
			services.push(JSON.parse(await readFile(join(ws, "services", name), "utf8")));
		const names = new Set(services.map((s) => s.name));
		const missing = services.flatMap((s) =>
			s.depends_on.filter((dep) => !names.has(dep)).map((dep) => `missing ${s.name} ${dep}`),
		);
		const ports = {};
		for (const s of services) ports[s.port] = [...(ports[s.port] ?? []), s.name];
		const clashes = Object.entries(ports)
			.filter(([, users]) => users.length > 1)
			.map(([port, users]) => `port ${port} ${users.sort().join(",")}`);
		await put(ws, [...missing.sort(), ...clashes.sort()].join("\n"));
	},
	"guix-build": async (ws) => {
		await edit(
			ws,
			"package.scm",
			'(local-file "greeting.txt")',
			'(local-file "files/greeting.txt")',
		);
		await edit(ws, "package.scm", "(mkdir share)", "(mkdir-p share)");
	},
	"jsonl-aggregate": async (ws) => {
		const counts = {};
		for (const line of (await readFile(join(ws, "events.jsonl"), "utf8")).split("\n")) {
			const event = JSON.parse(line);
			if (event.type === "tool") counts[event.name] = (counts[event.name] ?? 0) + 1;
		}
		const rows = Object.entries(counts).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
		await put(ws, rows.map(([name, count]) => `${name} ${count}`).join("\n"));
	},
};

let failures = 0;
const only = process.argv[2]?.split(",");
for (const task of TASKS.filter((t) => !only || only.includes(t.suite) || only.includes(t.id))) {
	const ws = await mkdtemp(join(tmpdir(), `pi-sci-eval-${task.id}-`));
	for (const [path, content] of Object.entries(task.files)) {
		await mkdir(dirname(join(ws, path)), { recursive: true });
		await writeFile(join(ws, path), content);
	}
	for (const path of task.executable ?? []) await chmod(join(ws, path), 0o755);
	await task.setup?.(ws);
	const before = await task.validate(ws);
	await (task.solve ?? SOLUTIONS[task.id])(ws);
	const after = await task.validate(ws);
	const ok = !before.passed && after.passed;
	if (!ok) failures++;
	console.log(
		`${ok ? "ok  " : "FAIL"} ${task.id} untouched=${before.passed} solved=${after.passed}${ok ? "" : ` ${JSON.stringify(after.detail)}`}`,
	);
	await rm(ws, { recursive: true, force: true });
}
process.exit(failures ? 1 : 0);

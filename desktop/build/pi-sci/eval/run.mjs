#!/usr/bin/env node
// Run the same goal-only tasks against several Pi harness profiles with one
// model and write one record per trial. See eval/README.md.
import { spawn } from "node:child_process";
import { createWriteStream, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { appendFile, chmod, mkdir, symlink, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { createInterface } from "node:readline";
import { finished } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import { readEvents, summarize } from "./metrics.mjs";
import { TASKS } from "./registry.mjs";

const desktop = fileURLToPath(new URL("../../../", import.meta.url));
const SCI = join(desktop, "pi/agent/extensions/sci-codemode/index.ts");
const GUIDANCE = join(desktop, "pi/agent/AGENTS.md");
const packages = join(homedir(), ".pi/agent/npm/node_modules");

// A provider that Pi loads from a package needs its extension and config file,
// which --no-extensions and the fresh agent directory would otherwise drop.
const PROVIDERS = {
	"claude-bridge": {
		extensions: [join(packages, "pi-claude-bridge/src/index.ts")],
		files: ["claude-bridge.json", "npm"],
	},
};
const PARSE_RULE =
	" Keep parsing, filtering, grouping, sorting, joins, and aggregation in SCI; do not generate temporary Python, JavaScript, shell, or Babashka programs for them.";

function withoutParseRule() {
	const text = readFileSync(GUIDANCE, "utf8");
	if (!text.includes(PARSE_RULE))
		throw new Error(
			"AGENTS.md no longer contains the parse rule; update PARSE_RULE in eval/run.mjs",
		);
	const path = join(homedir(), ".cache/pi-sci-eval/AGENTS-without-parse-rule.md");
	mkdirSync(dirname(path), { recursive: true });
	writeFileSync(path, text.replace(PARSE_RULE, ""));
	return path;
}

const sciSettings = (mode) => ({
	defaultTools: ["+codemode"],
	extensions: ["-builtin:codemode"],
	codemode: { mode },
});

// One variable per neighbouring profile: vanilla → sci-bare adds SCI-only
// codemode, sci-bare → sci adds the production AGENTS.md guidance, and
// sci-bare → hybrid also declares the plain tools. sci → sci-nopy drops the
// rule that forbids generating Python or shell programs for data work.
export const PROFILES = {
	vanilla: { args: ["--tools", "read,bash,edit,write"] },
	"sci-bare": { extensions: [SCI], settings: sciSettings("only") },
	sci: { extensions: [SCI], settings: sciSettings("only"), append: [GUIDANCE] },
	"sci-nopy": { extensions: [SCI], settings: sciSettings("only"), append: [withoutParseRule()] },
	hybrid: { extensions: [SCI], settings: sciSettings("on") },
};

function flags(argv) {
	const out = {};
	for (let i = 0; i < argv.length; i += 2) out[argv[i].replace(/^--/, "")] = argv[i + 1];
	return out;
}

const opts = flags(process.argv.slice(2));
if (!opts.model) throw new Error("--model is required");
const profiles = (opts.profiles ?? "vanilla,sci,hybrid").split(",");
for (const id of profiles) if (!PROFILES[id]) throw new Error(`unknown profile ${id}`);
const provider = PROVIDERS[opts.model.split("/")[0]] ?? {};
const tasks = TASKS.filter(
	(task) =>
		(!opts.tasks || opts.tasks.split(",").includes(task.id)) &&
		(!opts.suite || opts.suite.split(",").includes(task.suite)),
);
if (!tasks.length) throw new Error("no tasks match --tasks and --suite");
const attempts = Number(opts.attempts ?? 1);
const jobs = Number(opts.jobs ?? 3);
const timeoutMs = 1000 * Number(opts.timeout ?? 300);
const thinking = opts.thinking ?? "medium";
const retries = Number(opts.retries ?? 3);
const runId = opts.run ?? new Date().toISOString().replace(/[:.]/g, "-");
const out = resolve(opts.out ?? join(homedir(), ".cache/pi-sci-eval/runs"), runId);

// Profiles rotate per (attempt, task) so no profile always runs first, and the
// trials of one pair start next to each other and share machine noise.
const schedule = [];
for (let attempt = 1; attempt <= attempts; attempt++)
	tasks.forEach((task, index) => {
		const shift = (attempt + index) % profiles.length;
		const order = [...profiles.slice(shift), ...profiles.slice(0, shift)];
		for (const [position, profile] of order.entries())
			schedule.push({ task, attempt, profile, position });
	});

async function prepare(trial, dir) {
	const ws = join(dir, "ws");
	const agent = join(dir, "agent");
	await mkdir(agent, { recursive: true });
	for (const [path, content] of Object.entries(trial.task.files)) {
		await mkdir(dirname(join(ws, path)), { recursive: true });
		await writeFile(join(ws, path), content);
	}
	await mkdir(ws, { recursive: true });
	for (const path of trial.task.executable ?? []) await chmod(join(ws, path), 0o755);
	await trial.task.setup?.(ws);
	const source = process.env.PI_CODING_AGENT_DIR ?? join(homedir(), ".pi/agent");
	for (const name of ["auth.json", "models.json", "models-store.json", ...(provider.files ?? [])])
		if (existsSync(join(source, name))) await symlink(join(source, name), join(agent, name));
	const profile = PROFILES[trial.profile];
	await writeFile(
		join(agent, "settings.json"),
		JSON.stringify({ quietStartup: true, ...profile.settings }),
	);
	return { ws, agent };
}

function piArgs(trial, dir, prompt, turn) {
	const profile = PROFILES[trial.profile];
	return [
		"--no-extensions",
		...[...(provider.extensions ?? []), ...(profile.extensions ?? [])].flatMap((path) => [
			"--extension",
			path,
		]),
		"--no-context-files",
		"--no-skills",
		"--no-prompt-templates",
		"--no-themes",
		"--no-approve",
		"--session-dir",
		join(dir, "session"),
		...(turn > 0 ? ["--continue"] : []),
		"--model",
		opts.model,
		"--thinking",
		thinking,
		...(profile.append ?? []).flatMap((path) => ["--append-system-prompt", path]),
		...(profile.args ?? []),
		"--mode",
		"json",
		"-p",
		prompt,
	];
}

function runTurn(trial, dir, ws, agent, prompt, turn) {
	return new Promise((done) => {
		const child = spawn("pi", piArgs(trial, dir, prompt, turn), {
			cwd: ws,
			env: { ...process.env, PI_CODING_AGENT_DIR: agent },
			stdio: ["ignore", "pipe", "pipe"],
		});
		const events = createWriteStream(join(dir, "events.jsonl"), { flags: "a" });
		child.stdout.pipe(events);
		child.stderr.pipe(createWriteStream(join(dir, "stderr.txt"), { flags: "a" }));
		let last = { stopReason: null, errorMessage: null };
		createInterface({ input: child.stdout }).on("line", (line) => {
			if (!line.startsWith('{"type":"message_end"')) return;
			try {
				const { message } = JSON.parse(line);
				if (message?.role === "assistant")
					last = {
						stopReason: message.stopReason ?? null,
						errorMessage: message.errorMessage ?? null,
					};
			} catch {}
		});
		let timedOut = false;
		const timer = setTimeout(() => {
			timedOut = true;
			child.kill("SIGTERM");
		}, timeoutMs);
		child.on("close", (exit) => {
			clearTimeout(timer);
			finished(events).then(() => done({ exit, timedOut, ...last }));
		});
	});
}

const RATE_LIMITED = /rate.?limit|\b429\b|overloaded|\b529\b/i;

// A task with several prompts continues one session, so a later prompt can
// reuse whatever the agent built in an earlier one. A prompt that ends on a
// rate limit after Pi's own retries is sent again into the same session after
// a pause; invocations records how many times each prompt ran.
async function runPi(trial, dir, ws, agent) {
	const started = Date.now();
	const prompts = trial.task.prompts ?? [trial.task.prompt];
	const invocations = [];
	let last = { exit: 0, timedOut: false };
	for (const [turn, prompt] of prompts.entries()) {
		for (let attempt = 0; ; attempt++) {
			last = await runTurn(trial, dir, ws, agent, prompt, turn + attempt);
			const limited = last.stopReason === "error" && RATE_LIMITED.test(last.errorMessage ?? "");
			if (!limited || attempt >= retries) {
				invocations.push(attempt + 1);
				break;
			}
			await new Promise((resolve) => setTimeout(resolve, 30_000 * 2 ** attempt));
		}
		if (last.exit !== 0 || last.timedOut) break;
	}
	return {
		exit: last.exit,
		timedOut: last.timedOut,
		invocations,
		providerError: last.stopReason === "error" ? last.errorMessage : null,
		wallSeconds: (Date.now() - started) / 1000,
	};
}

async function runTrial(trial) {
	const name = `${trial.task.id}__a${trial.attempt}__${trial.profile}`;
	const dir = join(out, "trials", name);
	const { ws, agent } = await prepare(trial, dir);
	const process_ = await runPi(trial, dir, ws, agent);
	const events = readEvents(join(dir, "events.jsonl"));
	const grade = await trial.task.validate(ws);
	const record = {
		run: runId,
		model: opts.model,
		thinking,
		task: trial.task.id,
		category: trial.task.category,
		profile: trial.profile,
		attempt: trial.attempt,
		position: trial.position,
		passed: grade.passed,
		detail: grade.detail,
		...process_,
		...summarize(events),
	};
	await writeFile(join(dir, "result.json"), `${JSON.stringify(record, null, 2)}\n`);
	await appendFile(join(out, "results.jsonl"), `${JSON.stringify(record)}\n`);
	console.log(
		`${record.passed ? "PASS" : "FAIL"} ${name} ${record.wallSeconds.toFixed(1)}s calls=${record.outerCalls}/${record.innerCalls} err=${record.outerErrors}/${record.innerErrors} tok=${record.usage.totalTokens} $${record.cost.toFixed(4)}`,
	);
}

await mkdir(out, { recursive: true });
await writeFile(
	join(out, "manifest.json"),
	JSON.stringify(
		{
			runId,
			model: opts.model,
			thinking,
			profiles,
			tasks: tasks.map((task) => task.id),
			attempts,
			jobs,
			timeoutMs,
		},
		null,
		2,
	),
);
const queue = [...schedule];
await Promise.all(
	Array.from({ length: jobs }, async () => {
		while (queue.length) await runTrial(queue.shift());
	}),
);
console.log(`results: ${join(out, "results.jsonl")}`);

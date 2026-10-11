// biome-ignore-all lint/suspicious/noTemplateCurlyInString: fixture strings hold JavaScript source.
import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { lines, nodeTests, orders, random } from "./tasks.mjs";

// Long sessions: each prompt continues the same Pi session, so tool results
// and any helpers from earlier prompts stay in context or in the session.

const read = (ws, path) => readFile(join(ws, path), "utf8").catch(() => "");

function csvRow(line) {
	return [...line.matchAll(/("([^"]*)"|[^,]*)(,|$)/g)].slice(0, -1).map((m) => m[2] ?? m[1]);
}

function revenueText(csv) {
	const totals = {};
	const [, ...rows] = csv.trim().split("\n");
	for (const row of rows) {
		const [, , region, amount] = csvRow(row);
		const key = region.trim().toLowerCase();
		totals[key] = (totals[key] ?? 0) + Math.round(Number(amount.replace(/[$,]/g, "")) * 100);
	}
	return Object.keys(totals)
		.sort()
		.map((key) => `${key} ${(totals[key] / 100).toFixed(2)}`);
}

const MONTHS = Array.from({ length: 12 }, (_, i) => String(i + 1).padStart(2, "0"));
const LEDGER = Object.fromEntries(MONTHS.map((month) => [month, orders(100 + Number(month))]));

const monthly = {
	id: "monthly-reports",
	suite: "session",
	category: "helper-reuse",
	prompts: [
		"data/2026-01.csv is an order export. Compute the net revenue per region. Region names are case-insensitive and may have stray spaces, and refunds have negative amounts. Write `<region in lowercase> <total with two decimals>` lines, sorted by region, to reports/01.txt.",
		...MONTHS.slice(1).map(
			(month) =>
				`Do the same for data/2026-${month}.csv and write the result to reports/${month}.txt.`,
		),
	],
	files: Object.fromEntries(MONTHS.map((month) => [`data/2026-${month}.csv`, LEDGER[month].text])),
	async validate(ws) {
		const correct = [];
		for (const month of MONTHS)
			if (
				JSON.stringify(lines(await read(ws, `reports/${month}.txt`))) ===
				JSON.stringify(LEDGER[month].expected)
			)
				correct.push(month);
		return { passed: correct.length === MONTHS.length, detail: { correct } };
	},
	async solve(ws) {
		await mkdir(join(ws, "reports"), { recursive: true });
		for (const month of MONTHS)
			await writeFile(
				join(ws, `reports/${month}.txt`),
				`${revenueText(await read(ws, `data/2026-${month}.csv`)).join("\n")}\n`,
			);
	},
};

const ENDPOINTS = [
	"/api/users",
	"/api/orders",
	"/api/checkout",
	"/api/search",
	"/api/cart",
	"/health",
	"/api/login",
	"/api/items",
];
const FAILURE = { "/api/checkout": 0.06, "/api/search": 0.035, "/api/login": 0.02 };

function opsLogs() {
	const next = random(7);
	const files = {};
	const firstIds = [];
	for (let file = 1; file <= 4; file++) {
		const out = [];
		for (let i = 0; i < 15_000; i++) {
			const hour = i % 9 === 0 ? 14 : Math.floor((i * 24) / 15_000);
			const ts = new Date(Date.UTC(2026, 9, 4 + file, hour, i % 60, (i * 7) % 60))
				.toISOString()
				.replace(".000", "");
			const reused = file > 1 && i % 1500 === 7;
			const id = reused ? firstIds[i] : `r${file}${String(i).padStart(5, "0")}`;
			if (file === 1) firstIds.push(id);
			const path = ENDPOINTS[Math.floor(next() * ENDPOINTS.length)];
			const roll = next();
			const fail = FAILURE[path] ?? 0.004;
			const status =
				roll < fail ? [500, 502, 503, 500][Math.floor(next() * 4)] : roll < fail + 0.03 ? 404 : 200;
			const ms = Math.floor(next() * (path === "/api/checkout" ? 2400 : 600)) + 5;
			const user = `u${Math.floor(next() * 400)}`;
			out.push(`${ts} req=${id} user=${user} GET ${path} ${status} ${ms}ms`);
		}
		files[`logs/app-${file}.log`] = `${out.join("\n")}\n`;
	}
	return files;
}

function parseLogs(texts) {
	return texts.flatMap((text, file) =>
		text
			.trim()
			.split("\n")
			.map((line) => {
				const [ts, req, user, , path, status, ms] = line.split(" ");
				return {
					file,
					hour: ts.slice(11, 13),
					id: req.slice(4),
					user: user.slice(5),
					path,
					status: Number(status),
					ms: Number.parseInt(ms, 10),
				};
			}),
	);
}

// Two-decimal percentages in exact integer arithmetic. An exact half, such as
// 1.755, accepts both neighbours, since half-up and half-even disagree and
// floats land on either side.
function percent(part, whole) {
	const scaled = part * 10_000;
	const low = Math.floor(scaled / whole);
	const format = (hundredths) =>
		`${Math.floor(hundredths / 100)}.${String(hundredths % 100).padStart(2, "0")}`;
	if (scaled % whole === 0) return [format(low)];
	if (2 * (scaled % whole) === whole) return [format(low + 1), format(low)];
	return [format(2 * (scaled % whole) > whole ? low + 1 : low)];
}

function opsAnswers(texts, configs, orderCsv) {
	const rows = parseLogs(texts);
	const five = rows.filter((row) => row.status >= 500);
	const byPath = {};
	for (const row of five) byPath[row.path] = (byPath[row.path] ?? 0) + 1;
	const worst = Object.entries(byPath).sort(
		(a, b) => b[1] - a[1] || a[0].localeCompare(b[0]),
	)[0][0];
	const ms = rows
		.filter((row) => row.path === worst)
		.map((row) => row.ms)
		.sort((a, b) => a - b);
	const p95 = ms[Math.ceil(0.95 * ms.length) - 1];
	const hours = {};
	for (const row of rows) hours[row.hour] = (hours[row.hour] ?? 0) + 1;
	const busiest = Object.entries(hours).sort(
		(a, b) => b[1] - a[1] || a[0].localeCompare(b[0]),
	)[0][0];
	const flatten = (value, prefix = "") =>
		value && typeof value === "object" && !Array.isArray(value)
			? Object.entries(value).flatMap(([key, item]) =>
					flatten(item, prefix ? `${prefix}.${key}` : key),
				)
			: [[prefix, JSON.stringify(value)]];
	const [staging, production] = configs.map((text) => new Map(flatten(JSON.parse(text))));
	const diff = [...new Set([...staging.keys(), ...production.keys()])]
		.filter((key) => staging.get(key) !== production.get(key))
		.sort();
	const users = {};
	for (const row of rows.filter((row) => row.status === 500))
		users[row.user] = (users[row.user] ?? 0) + 1;
	const heavy = Object.entries(users)
		.filter(([, n]) => n >= 3)
		.map(([user]) => user)
		.sort();
	const files = {};
	for (const row of rows) files[row.id] = new Set([...(files[row.id] ?? []), row.file]);
	const shared = Object.entries(files)
		.filter(([, set]) => set.size > 1)
		.map(([id]) => id)
		.sort();
	return {
		"01": [`${texts.length} ${rows.length}`],
		"02": [worst],
		"03": [String(p95)],
		"04": [busiest],
		"05": diff,
		"09": heavy,
		10: revenueText(orderCsv),
		11: shared,
		12: [`${rows.length} ${five.length} ${percent(five.length, rows.length)[0]}`],
		rates: percent(five.length, rows.length).map((rate) => `${rows.length} ${five.length} ${rate}`),
		timeout: Math.ceil(p95 / 100) * 100,
	};
}

const CONFIGS = {
	"config/staging.json": `${JSON.stringify({ server: { port: 8080, timeout_ms: 5000, workers: 2 }, db: { host: "db.staging", pool: 5 }, features: { search: true, beta: true }, log: "debug" }, null, 2)}\n`,
	"config/production.json": `${JSON.stringify({ server: { port: 8080, timeout_ms: 3000, workers: 8 }, db: { host: "db.prod", pool: 20, replica: "db.prod-ro" }, features: { search: true }, log: "info" }, null, 2)}\n`,
};

const SERVICE_FILES = {
	"package.json": '{"type":"module"}\n',
	"src/text.js":
		"export function truncate(text, length) {\n\treturn text.length > length ? `${text.slice(0, length - 1)}…` : text;\n}\n",
	"src/users.js":
		'const USERS = { u1: "Ada", u2: "Grace" };\n\nexport async function fetchUser(id) {\n\treturn USERS[id] ?? null;\n}\n',
	"src/app.js":
		'import { fetchUser } from "./users.js";\n\nexport async function greet(id) {\n\tconst name = await fetchUser(id);\n\treturn name ? `hello ${name}` : "hello stranger";\n}\n',
	"test/text.test.js":
		'import assert from "node:assert/strict";\nimport test from "node:test";\nimport { truncate } from "../src/text.js";\n\ntest("truncate keeps length characters before the ellipsis", () => {\n\tassert.equal(truncate("abcdef", 3), "abc…");\n\tassert.equal(truncate("ab", 3), "ab");\n});\n',
	"test/users.test.js":
		'import assert from "node:assert/strict";\nimport test from "node:test";\nimport { fetchUser } from "../src/users.js";\nimport { greet } from "../src/app.js";\n\ntest("fetchUser finds known users", async () => {\n\tassert.equal(await fetchUser("u1"), "Ada");\n\tassert.equal(await greet("u9"), "hello stranger");\n});\n',
	"README.md": "# Service\n\nUse `fetchUser(id)` to look up a user.\n",
};

const OPS_FILES = {
	...opsLogs(),
	...CONFIGS,
	...SERVICE_FILES,
	"data/orders.csv": orders(77).text,
};
const LOGS = [1, 2, 3, 4].map((file) => OPS_FILES[`logs/app-${file}.log`]);
const EXPECTED = opsAnswers(LOGS, Object.values(CONFIGS), OPS_FILES["data/orders.csv"]);

const SLUGS = [
	["Hello, World!", "hello-world"],
	["  Guix  Home -- Setup ", "guix-home-setup"],
	["already-a-slug", "already-a-slug"],
	["Ünïcode & Co.", "n-code-co"],
];

async function slugifyWorks(ws) {
	try {
		const module = await import(`${pathToFileURL(join(ws, "src/text.js")).href}?t=${Date.now()}`);
		return SLUGS.every(([input, output]) => module.slugify(input) === output);
	} catch {
		return false;
	}
}

const ops = {
	id: "ops-day",
	suite: "session",
	category: "long-session",
	prompts: [
		"How many log files are in logs/, and how many lines do they have in total? Write `<files> <lines>` to answers/01.txt.",
		"Which endpoint (the path field) has the most 5xx responses across all logs? Write just the path to answers/02.txt.",
		"For the endpoint in answers/02.txt, compute the p95 latency in ms over all of its requests, using the nearest-rank method. Write the integer to answers/03.txt.",
		"Which hour of the day (UTC, two digits) has the most requests across all logs? Write it to answers/04.txt.",
		"List every config key, as a dotted path such as server.port, whose value differs between config/staging.json and config/production.json, including keys that exist in only one of them. Write them sorted, one per line, to answers/05.txt.",
		"The test suite fails. Fix the implementation in src/ so `node --test` passes. Do not change the tests.",
		"Add an exported function `slugify(text)` to src/text.js. It lowercases the text, turns every run of characters other than a-z and 0-9 into a single hyphen, and trims hyphens from both ends.",
		"Rename `fetchUser` to `loadUser` everywhere in src/, test/, and README.md. `node --test` must still pass.",
		"Which users (the user field) had at least three requests with status exactly 500 across all logs? Write them sorted, one per line, to answers/09.txt.",
		"data/orders.csv is an order export. Compute the net revenue per region. Region names are case-insensitive and may have stray spaces, and refunds have negative amounts. Write `<region in lowercase> <total with two decimals>` lines, sorted by region, to answers/10.txt.",
		"Which request ids (the req field) appear in more than one log file? Write them sorted, one per line, to answers/11.txt.",
		"Write `<total requests> <5xx responses> <5xx rate as a percentage with two decimals>` for all logs to answers/12.txt.",
		"Set server.timeout_ms in config/production.json to the p95 from answers/03.txt rounded up to the next multiple of 100. Leave every other value unchanged.",
	],
	files: OPS_FILES,
	async validate(ws) {
		const checks = {};
		for (const [step, expected] of Object.entries(EXPECTED))
			if (step === "12")
				checks[step] = EXPECTED.rates.includes(lines(await read(ws, "answers/12.txt")).join(" "));
			else if (step !== "timeout" && step !== "rates")
				checks[step] =
					JSON.stringify(lines(await read(ws, `answers/${step}.txt`))) === JSON.stringify(expected);
		const tests = await read(ws, "test/text.test.js");
		checks["06"] = tests === SERVICE_FILES["test/text.test.js"] && nodeTests(ws);
		checks["07"] = await slugifyWorks(ws);
		const renamed = ["src/users.js", "src/app.js", "test/users.test.js", "README.md"];
		const leftovers = [];
		for (const path of renamed)
			if ((await read(ws, path)).includes("fetchUser")) leftovers.push(path);
		checks["08"] = leftovers.length === 0 && nodeTests(ws);
		try {
			const production = JSON.parse(await read(ws, "config/production.json"));
			const original = JSON.parse(CONFIGS["config/production.json"]);
			checks["13"] =
				production.server.timeout_ms === EXPECTED.timeout &&
				JSON.stringify({ ...production, server: { ...production.server, timeout_ms: 0 } }) ===
					JSON.stringify({ ...original, server: { ...original.server, timeout_ms: 0 } });
		} catch {
			checks["13"] = false;
		}
		const failed = Object.entries(checks)
			.filter(([, ok]) => !ok)
			.map(([step]) => step)
			.sort();
		return { passed: failed.length === 0, detail: { failed, score: `${13 - failed.length}/13` } };
	},
	async solve(ws) {
		const texts = [];
		for (const name of (await readdir(join(ws, "logs"))).sort())
			texts.push(await read(ws, `logs/${name}`));
		const answers = opsAnswers(
			texts,
			[await read(ws, "config/staging.json"), await read(ws, "config/production.json")],
			await read(ws, "data/orders.csv"),
		);
		await mkdir(join(ws, "answers"), { recursive: true });
		for (const [step, value] of Object.entries(answers))
			if (step !== "timeout" && step !== "rates")
				await writeFile(join(ws, `answers/${step}.txt`), `${value.join("\n")}\n`);
		await writeFile(
			join(ws, "src/text.js"),
			'export function truncate(text, length) {\n\treturn text.length > length ? `${text.slice(0, length)}…` : text;\n}\n\nexport function slugify(text) {\n\treturn text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");\n}\n',
		);
		for (const path of ["src/users.js", "src/app.js", "test/users.test.js", "README.md"])
			await writeFile(join(ws, path), (await read(ws, path)).replaceAll("fetchUser", "loadUser"));
		const production = JSON.parse(await read(ws, "config/production.json"));
		production.server.timeout_ms = answers.timeout;
		await writeFile(join(ws, "config/production.json"), `${JSON.stringify(production, null, 2)}\n`);
	},
};

export const SESSION_TASKS = [monthly, ops];

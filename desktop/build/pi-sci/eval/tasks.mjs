import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { join } from "node:path";

// Each task owns its fixture and an executable grader. Prompts state the goal
// only, never which tool to use.

async function answer(ws) {
	try {
		return (await readFile(join(ws, "answer.txt"), "utf8")).trim();
	} catch {
		return null;
	}
}

function lines(text) {
	return (text ?? "")
		.split("\n")
		.map((line) => line.trim())
		.filter(Boolean);
}

function nodeTests(ws) {
	try {
		execFileSync("node", ["--test"], { cwd: ws, stdio: "pipe", timeout: 60_000 });
		return true;
	} catch {
		return false;
	}
}

const PACKAGES = [
	["ripgrep", "cargo"],
	["fd", "cargo"],
	["jq", "gnu"],
	["hello", "gnu"],
	["bat", "cargo"],
	["httpie", "pyproject"],
	["black", "pyproject"],
	["zoxide", "cargo"],
	["sed", "gnu"],
	["tealdeer", "cargo"],
	["mypy", "pyproject"],
	["grep", "gnu"],
	["hyperfine", "cargo"],
	["tmux", "gnu"],
	["ruff", "pyproject"],
];

function packageModule(names) {
	return names
		.map(([name, system]) =>
			[
				`(define-public ${name}`,
				"  (package",
				`    (name "${name}")`,
				'    (version "1.0.0")',
				`    (build-system ${system}-build-system)`,
				`    (synopsis "The ${name} tool")`,
				"    (license license:expat)))",
				"",
			].join("\n"),
		)
		.join("\n");
}

function buildLog() {
	const out = [];
	const errors = new Map([
		[4211, "error: failed to compile `src/lexer.rs`: expected `;`, found `}`"],
		[11873, "error: undefined reference to `ssl_ctx_new'"],
		[17302, "error: test `parser::roundtrip` panicked: assertion failed"],
	]);
	for (let i = 1; i <= 20_000; i++) {
		if (errors.has(i)) out.push(errors.get(i));
		else if (i % 997 === 0)
			out.push(`warning: unused variable \`tmp${i}\` (deprecated error handling)`);
		else out.push(`   Compiling crate-${i % 311} v0.${i % 17}.${i % 5} (step ${i})`);
	}
	return { text: `${out.join("\n")}\n`, errors: [...errors.values()] };
}

const LOG = buildLog();

export const TASKS = [
	{
		id: "discover-executable",
		category: "tool-discovery",
		prompt:
			"Find the absolute path of the `jq` executable on this machine, as resolved through PATH. Write only that path to answer.txt.",
		files: {},
		async validate(ws) {
			const expected = execFileSync("sh", ["-c", "command -v jq"]).toString().trim();
			const got = await answer(ws);
			return { passed: got === expected, detail: { got, expected } };
		},
	},
	{
		id: "navigate-service",
		category: "navigation",
		prompt:
			"This repository is a small Guix system configuration. Find which file configures the OpenSSH service and which port it listens on. Write `<path relative to the repository root>:<port>` to answer.txt.",
		files: {
			"system/base.scm":
				'(use-modules (gnu) (machines desktop))\n(operating-system\n  (host-name "m1")\n  (services %desktop-services))\n',
			"system/machines/desktop.scm":
				"(define-module (machines desktop)\n  #:use-module (services remote)\n  #:export (%desktop-services))\n(define %desktop-services\n  (list %remote-access (service ntp-service-type)))\n",
			"system/services/remote.scm":
				"(define-module (services remote)\n  #:export (%remote-access))\n;; Port 22 is blocked upstream.\n(define %remote-access\n  (service openssh-service-type\n    (openssh-configuration\n      (port-number 2222)\n      (password-authentication? #f))))\n",
			"system/services/web.scm":
				"(define-module (services web))\n(define %web (service nginx-service-type (nginx-configuration (port 8022))))\n",
			"README.md": "# Config\n\nSSH used to run on port 22.\n",
		},
		async validate(ws) {
			const got = await answer(ws);
			return { passed: got === "system/services/remote.scm:2222", detail: { got } };
		},
	},
	{
		id: "fix-module",
		category: "code-modification",
		prompt:
			"The test suite in this project fails. Fix the implementation so `node --test` passes. Do not modify the tests.",
		files: {
			"package.json": '{"type":"module"}\n',
			"src/version.js":
				'export function compareVersions(a, b) {\n\tconst left = a.split(".");\n\tconst right = b.split(".");\n\tfor (let i = 0; i < Math.max(left.length, right.length); i++) {\n\t\tconst x = left[i] ?? "0";\n\t\tconst y = right[i] ?? "0";\n\t\tif (x !== y) return x < y ? -1 : 1;\n\t}\n\treturn 0;\n}\n',
			"test/version.test.js":
				'import assert from "node:assert/strict";\nimport test from "node:test";\nimport { compareVersions } from "../src/version.js";\n\ntest("orders numerically", () => {\n\tassert.equal(compareVersions("1.10.0", "1.9.0"), 1);\n\tassert.equal(compareVersions("1.2", "1.2.0"), 0);\n\tassert.equal(compareVersions("0.9", "0.10"), -1);\n});\n',
		},
		async validate(ws) {
			const test = await readFile(join(ws, "test/version.test.js"), "utf8");
			const untouched = test.includes('compareVersions("0.9", "0.10"), -1');
			const passed = untouched && nodeTests(ws);
			return { passed, detail: { untouched } };
		},
	},
	{
		id: "rename-function",
		category: "multi-file-refactor",
		prompt:
			"Rename the function `parseConfig` to `loadConfig` everywhere in this project, including its tests and documentation. `node --test` must still pass.",
		files: {
			"package.json": '{"type":"module"}\n',
			"src/config.js":
				'export function parseConfig(text) {\n\treturn Object.fromEntries(text.split("\\n").filter(Boolean).map((line) => line.split("=")));\n}\n',
			"src/app.js":
				'import { parseConfig } from "./config.js";\n\nexport function start(text) {\n\tconst config = parseConfig(text);\n\treturn config.name ?? "anonymous";\n}\n',
			"src/cli.js":
				'import { parseConfig as parse } from "./config.js";\n\nexport const read = (text) => parse(text);\n',
			"test/config.test.js":
				'import assert from "node:assert/strict";\nimport test from "node:test";\nimport { parseConfig } from "../src/config.js";\nimport { start } from "../src/app.js";\n\ntest("parseConfig reads pairs", () => {\n\tassert.deepEqual(parseConfig("a=1\\nb=2"), { a: "1", b: "2" });\n\tassert.equal(start("name=pi"), "pi");\n});\n',
			"README.md": "# Config\n\nCall `parseConfig(text)` to read a config file.\n",
		},
		async validate(ws) {
			const files = [
				"src/config.js",
				"src/app.js",
				"src/cli.js",
				"test/config.test.js",
				"README.md",
			];
			const left = [];
			for (const file of files)
				if ((await readFile(join(ws, file), "utf8")).includes("parseConfig")) left.push(file);
			const passed = left.length === 0 && nodeTests(ws);
			return { passed, detail: { left } };
		},
	},
	{
		id: "structured-analysis",
		category: "structured-analysis",
		prompt:
			"List every package in this repository that uses the cargo build system. Write their package names to answer.txt, one per line, sorted alphabetically.",
		files: {
			"packages/rust.scm": packageModule(PACKAGES.slice(0, 5)),
			"packages/python.scm": packageModule(PACKAGES.slice(5, 11)),
			"packages/misc.scm": packageModule(PACKAGES.slice(11)),
		},
		async validate(ws) {
			const expected = PACKAGES.filter(([, system]) => system === "cargo")
				.map(([name]) => name)
				.sort();
			const got = lines(await answer(ws));
			return {
				passed: JSON.stringify(got) === JSON.stringify(expected),
				detail: { got, expected },
			};
		},
	},
	{
		id: "large-output",
		category: "large-output",
		prompt:
			"build.log is the output of a failed build. Find the lines that report errors (not warnings) and write each one verbatim to answer.txt, one per line.",
		files: { "build.log": LOG.text },
		async validate(ws) {
			const got = lines(await answer(ws));
			const passed =
				got.length === LOG.errors.length && LOG.errors.every((error) => got.includes(error));
			return { passed, detail: { got } };
		},
	},
	{
		id: "failure-recovery",
		category: "failure-recovery",
		prompt: "Read config/settings.json and write the value of its `timeout` field to answer.txt.",
		files: {
			"conf/settings.json": '{"name":"svc","timeout":37,"retries":2}\n',
			"conf/defaults.json": '{"timeout":30}\n',
		},
		async validate(ws) {
			const got = await answer(ws);
			return { passed: got === "37", detail: { got } };
		},
	},
	{
		id: "parallel-checks",
		category: "async-execution",
		prompt:
			"Run each of the three check scripts in checks/ (each takes a few seconds). Write the file name of every script that fails to answer.txt, one per line.",
		files: {
			"checks/lint.sh": "#!/bin/sh\nsleep 4\necho lint ok\n",
			"checks/types.sh": "#!/bin/sh\nsleep 4\necho 'types: 2 errors' >&2\nexit 1\n",
			"checks/format.sh": "#!/bin/sh\nsleep 4\necho format ok\n",
		},
		executable: ["checks/lint.sh", "checks/types.sh", "checks/format.sh"],
		async validate(ws) {
			const got = lines(await answer(ws));
			return { passed: JSON.stringify(got) === JSON.stringify(["types.sh"]), detail: { got } };
		},
	},
	{
		id: "jsonl-aggregate",
		category: "structured-analysis",
		prompt:
			'events.jsonl holds one JSON event per line. Count the events with type "tool" per `name` field and write `<name> <count>` lines to answer.txt, sorted by count descending, then name.',
		files: {
			"events.jsonl": Array.from({ length: 600 }, (_, i) =>
				JSON.stringify(
					i % 5 === 0
						? { type: "message", text: `note ${i} name=read` }
						: {
								type: "tool",
								name: ["read", "bash", "edit", "read", "grep", "bash", "read"][i % 7],
								ms: i % 13,
							},
				),
			).join("\n"),
		},
		async validate(ws) {
			const counts = {};
			for (let i = 0; i < 600; i++)
				if (i % 5 !== 0) {
					const name = ["read", "bash", "edit", "read", "grep", "bash", "read"][i % 7];
					counts[name] = (counts[name] ?? 0) + 1;
				}
			const expected = Object.entries(counts)
				.sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
				.map(([name, count]) => `${name} ${count}`);
			const got = lines(await answer(ws));
			return {
				passed: JSON.stringify(got) === JSON.stringify(expected),
				detail: { got, expected },
			};
		},
	},
];

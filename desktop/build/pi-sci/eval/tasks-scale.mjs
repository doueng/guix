import { execFileSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import { readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { answer, lines, services } from "./tasks.mjs";

// The same three tasks at three sizes, to see whether the SCI/vanilla cost
// ratio changes as the data grows.

const put = (ws, text) => writeFile(join(ws, "answer.txt"), `${text}\n`);

function checks(count) {
	const names = Array.from(
		{ length: count },
		(_, i) => `check-${String(i + 1).padStart(3, "0")}.sh`,
	);
	const failing = names.filter((_, i) => i % 5 === 2);
	return {
		id: `scale-checks-${count}`,
		suite: "scale",
		category: "scale-checks",
		size: count,
		prompt:
			"Run every script in checks/. Each takes about two seconds. Write the file names of the scripts that fail to answer.txt, one per line, sorted.",
		files: Object.fromEntries(
			names.map((name) => [
				`checks/${name}`,
				`#!/bin/sh\nsleep 2\n${failing.includes(name) ? `echo '${name}: failed' >&2\nexit 1` : "echo ok"}\n`,
			]),
		),
		executable: names.map((name) => `checks/${name}`),
		async validate(ws) {
			const got = lines(await answer(ws)).map((line) => line.split("/").at(-1));
			return {
				passed: JSON.stringify(got) === JSON.stringify(failing),
				detail: { got: got.slice(0, 20) },
			};
		},
		async solve(ws) {
			const failed = [];
			for (const name of (await readdir(join(ws, "checks"))).sort())
				try {
					execFileSync(join(ws, "checks", name), { stdio: "ignore" });
				} catch {
					failed.push(name);
				}
			await put(ws, failed.join("\n"));
		},
	};
}

function joins(count) {
	const fixture = services(count, count);
	return {
		id: `scale-services-${count}`,
		suite: "scale",
		category: "scale-services",
		size: count,
		prompt:
			"services/ holds one JSON file per service with `name`, `port`, and `depends_on`. Write to answer.txt one `missing <service> <dependency>` line for every dependency that names no existing service, then one `port <port> <services>` line for every port used by more than one service, with the services sorted and comma-separated. Sort the missing lines, then the port lines.",
		files: fixture.files,
		async validate(ws) {
			const got = lines(await answer(ws));
			return {
				passed: JSON.stringify(got) === JSON.stringify(fixture.expected),
				detail: { got: got.length, expected: fixture.expected.length },
			};
		},
		async solve(ws) {
			const all = [];
			for (const name of await readdir(join(ws, "services")))
				all.push(JSON.parse(await readFile(join(ws, "services", name), "utf8")));
			const names = new Set(all.map((s) => s.name));
			const missing = all.flatMap((s) =>
				s.depends_on.filter((dep) => !names.has(dep)).map((dep) => `missing ${s.name} ${dep}`),
			);
			const ports = {};
			for (const s of all) ports[s.port] = [...(ports[s.port] ?? []), s.name];
			const clashes = Object.entries(ports)
				.filter(([, users]) => users.length > 1)
				.map(([port, users]) => `port ${port} ${users.sort().join(",")}`);
			await put(ws, [...missing.sort(), ...clashes.sort()].join("\n"));
		},
	};
}

const MESSAGES = [
	"database timeout after 30s",
	"upstream returned 502",
	"cache miss storm on key users",
	"payment provider rejected request",
	"disk quota exceeded on /var/data",
	"invalid session token",
];

function logLine(i) {
	const ts = new Date(Date.UTC(2026, 9, 1) + i * 997).toISOString();
	if (i % 97 === 13) return `${ts} ERROR ${MESSAGES[((i * 7) % 11) % MESSAGES.length]}`;
	if (i % 89 === 5) return `${ts} WARN retrying error handler for request ${i}`;
	return `${ts} INFO request id=${i} path=/api/${i % 23} status=200 ms=${i % 400}`;
}

function errorLog(count) {
	const counts = {};
	for (let i = 0; i < count; i++) {
		const line = logLine(i);
		const at = line.indexOf(" ERROR ");
		if (at !== -1) counts[line.slice(at + 7)] = (counts[line.slice(at + 7)] ?? 0) + 1;
	}
	const expected = Object.entries(counts)
		.sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
		.map(([message, n]) => `${n} ${message}`);
	return {
		id: `scale-log-${count}`,
		suite: "scale",
		category: "scale-log",
		size: count,
		prompt:
			"app.log is an application log. Count how many times each distinct ERROR message occurs (the text after `ERROR `). Write `<count> <message>` lines to answer.txt, sorted by count descending, then by message.",
		files: {},
		setup: (ws) =>
			new Promise((done, fail) => {
				const out = createWriteStream(join(ws, "app.log"));
				out.on("error", fail);
				out.on("finish", done);
				let chunk = [];
				for (let i = 0; i < count; i++) {
					chunk.push(logLine(i));
					if (chunk.length === 10_000) {
						out.write(`${chunk.join("\n")}\n`);
						chunk = [];
					}
				}
				if (chunk.length) out.write(`${chunk.join("\n")}\n`);
				out.end();
			}),
		async validate(ws) {
			const got = lines(await answer(ws));
			return {
				passed: JSON.stringify(got) === JSON.stringify(expected),
				detail: { got, expected },
			};
		},
		async solve(ws) {
			const out = execFileSync(
				"sh",
				["-c", "grep ' ERROR ' app.log | sed 's/.* ERROR //' | sort | uniq -c"],
				{
					cwd: ws,
					maxBuffer: 1 << 26,
				},
			)
				.toString()
				.trim()
				.split("\n")
				.map((line) => line.trim().match(/^(\d+) (.*)$/))
				.map(([, n, message]) => [Number(n), message])
				.sort((a, b) => b[0] - a[0] || a[1].localeCompare(b[1]))
				.map(([n, message]) => `${n} ${message}`);
			await put(ws, out.join("\n"));
		},
	};
}

export const SCALE_TASKS = [
	...[4, 16, 48].map(checks),
	...[20, 200, 1000].map(joins),
	...[10_000, 100_000, 500_000].map(errorLog),
];

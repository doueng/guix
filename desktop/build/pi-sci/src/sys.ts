import { constants } from "node:fs";
import { access, readdir, readFile, statfs } from "node:fs/promises";
import { arch, cpus, hostname, loadavg, platform, release, uptime } from "node:os";
import { delimiter, join, resolve } from "node:path";
import { Type } from "typebox";
import { collection, type Domain, limit, operation, string, strings } from "./operations.ts";

const optional = Type.Optional;
const SENSITIVE = /KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|COOKIE|AUTH/i;

async function meminfo() {
	const fields = Object.fromEntries(
		(await readFile("/proc/meminfo", "utf8"))
			.split("\n")
			.map((line) => /^(\w+):\s+(\d+) kB$/.exec(line))
			.filter((match) => match !== null)
			.map((match) => [match[1], Number(match[2]) * 1024]),
	);
	return {
		total_bytes: fields.MemTotal,
		available_bytes: fields.MemAvailable,
		swap_total_bytes: fields.SwapTotal,
		swap_free_bytes: fields.SwapFree,
	};
}

async function executable(path: string) {
	try {
		await access(path, constants.X_OK);
		return true;
	} catch {
		return false;
	}
}

export const sysDomain: Domain = {
	local: true,
	id: "sys",
	namespace: "pi.sys",
	alias: "sys",
	description:
		"Read-only host facts: disk space, memory, selected environment variables, executables on PATH, processes, and OS identity. Values of sensitive-looking variable names are redacted.",
	operations: {
		disk: operation(
			Type.Object({ path: optional(string) }),
			async (a, e) => {
				const path = resolve(e.ctx.cwd, a.cwd ?? ".", a.path ?? ".");
				const s = await statfs(path);
				const total = s.blocks * s.bsize;
				return {
					path,
					total_bytes: total,
					free_bytes: s.bfree * s.bsize,
					available_bytes: s.bavail * s.bsize,
					used_percent: total ? Math.round(((s.blocks - s.bfree) / s.blocks) * 1000) / 10 : 0,
				};
			},
			"path",
		),
		memory: operation(Type.Object({}), async () => meminfo()),
		env: operation(
			Type.Object({ names: optional(strings), prefix: optional(string) }),
			async (a) => {
				if (!a.names && a.prefix === undefined) throw new Error("Give names or prefix");
				const names =
					a.names ?? Object.keys(process.env).filter((n) => n.startsWith(a.prefix ?? ""));
				return {
					values: Object.fromEntries(
						names
							.sort()
							.map((name) => [
								name,
								process.env[name] === undefined
									? null
									: SENSITIVE.test(name)
										? "[redacted]"
										: process.env[name],
							]),
					),
				};
			},
			"names",
		),
		which: operation(
			Type.Object({ programs: strings }),
			async (a) => {
				const dirs = (process.env.PATH ?? "").split(delimiter).filter(Boolean);
				const found: Record<string, string | null> = {};
				for (const program of a.programs) {
					found[program] = null;
					for (const dir of program.includes("/") ? [""] : dirs) {
						const candidate = dir ? join(dir, program) : program;
						if (await executable(candidate)) {
							found[program] = candidate;
							break;
						}
					}
				}
				return { found };
			},
			"programs",
		),
		processes: operation(
			Type.Object({ name: optional(string), limit }),
			async (a, e) => {
				const items = [];
				for (const pid of (await readdir("/proc")).filter((entry) => /^\d+$/.test(entry))) {
					e.signal?.throwIfAborted();
					try {
						const comm = (await readFile(`/proc/${pid}/comm`, "utf8")).trim();
						const cmdline = (await readFile(`/proc/${pid}/cmdline`, "utf8"))
							.split("\0")
							.join(" ")
							.trim()
							.slice(0, 500);
						if (a.name && !comm.includes(a.name) && !cmdline.includes(a.name)) continue;
						items.push({ pid: Number(pid), comm, cmdline });
					} catch {}
				}
				return collection(items, a.limit ?? 100);
			},
			"name",
		),
		info: operation(Type.Object({}), async () => ({
			platform: platform(),
			release: release(),
			arch: arch(),
			hostname: hostname(),
			uptime_seconds: Math.round(uptime()),
			cpus: cpus().length,
			loadavg: loadavg(),
		})),
	},
};

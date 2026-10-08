import { access, readFile } from "node:fs/promises";
import { dirname, isAbsolute, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Type } from "typebox";
import {
	collection,
	command,
	complete,
	type Domain,
	type Execution,
	type Options,
	operation,
	string,
	strings,
} from "./operations.ts";

const optional = Type.Optional;
const buildInput = {
	file: optional(string),
	packages: optional(strings),
	manifest: optional(string),
	load_paths: optional(strings),
	substitute_urls: optional(strings),
	system: optional(string),
	dry_run: optional(Type.Boolean()),
	no_substitutes: optional(Type.Boolean()),
};
function guixFlags(a: {
	file?: string;
	packages?: string[];
	manifest?: string;
	load_paths?: string[];
	substitute_urls?: string[];
	system?: string;
	dry_run?: boolean;
	no_substitutes?: boolean;
}) {
	if (a.file && (a.packages?.length || a.manifest))
		throw new Error("Use a file OR packages/manifest");
	return [
		...(a.file ? ["--file", a.file] : []),
		...(a.manifest ? ["--manifest", a.manifest] : []),
		...(a.load_paths ?? []).flatMap((p) => ["-L", p]),
		...(a.substitute_urls ? [`--substitute-urls=${a.substitute_urls.join(" ")}`] : []),
		...(a.system ? ["--system", a.system] : []),
		...(a.dry_run ? ["--dry-run"] : []),
		...(a.no_substitutes ? ["--no-substitutes"] : []),
		"--",
		...(a.packages ?? []),
	];
}
async function guix(argv: string[], a: Options, e: Execution) {
	return command("guix", argv, a, e);
}
function outputs(r: Awaited<ReturnType<typeof guix>>) {
	const lines = r.stdout.split("\n").filter(Boolean);
	if (r.ok && !r.truncated && lines.some((line) => !line.startsWith("/gnu/store/")))
		throw new Error("Unexpected Guix build stdout; outputs cannot be normalized");
	return {
		...r,
		outputs: r.ok && !r.truncated ? lines : [],
		outputs_complete: r.ok && !r.truncated,
	};
}
export const guixDomain: Domain = {
	id: "guix",
	namespace: "pi.guix",
	alias: "guix",
	description:
		"Guix build and environment operations. No activation or reconfigure operation is provided.",
	operations: {
		build: operation(Type.Object(buildInput), async (a, e) =>
			outputs(await guix(["build", ...guixFlags(a)], a, e)),
		),
		"system-build": operation(
			Type.Object({
				config: string,
				load_paths: optional(strings),
				substitute_urls: optional(strings),
				dry_run: optional(Type.Boolean()),
			}),
			async (a, e) =>
				outputs(
					await guix(
						[
							"system",
							"build",
							...(a.load_paths ?? []).flatMap((p) => ["-L", p]),
							...(a.substitute_urls ? [`--substitute-urls=${a.substitute_urls.join(" ")}`] : []),
							...(a.dry_run ? ["--dry-run"] : []),
							"--",
							a.config,
						],
						a,
						e,
					),
				),
		),
		shell: operation(
			Type.Object({
				packages: optional(strings),
				manifest: optional(string),
				container: optional(Type.Boolean()),
				pure: optional(Type.Boolean()),
				command: strings,
			}),
			async (a, e) => {
				if (!a.command.length) throw new Error("Guix shell requires a noninteractive command");
				return guix(
					[
						"shell",
						...(a.manifest ? ["-m", a.manifest] : []),
						...(a.container ? ["--container"] : []),
						...(a.pure ? ["--pure"] : []),
						...(a.packages ?? []),
						"--",
						...a.command,
					],
					a,
					e,
				);
			},
		),
		weather: operation(
			Type.Object({
				packages: optional(strings),
				manifest: optional(string),
				substitute_urls: optional(strings),
			}),
			async (a, e) => guix(["weather", ...guixFlags(a)], a, e),
		),
		describe: operation(Type.Object({}), async (a, e) => guix(["describe"], a, e)),
		search: operation(
			Type.Object({ query: string }),
			async (a, e) => guix(["search", "--", a.query], a, e),
			"query",
		),
		show: operation(
			Type.Object({ package: string }),
			async (a, e) => guix(["show", "--", a.package], a, e),
			"package",
		),
		version: operation(Type.Object({}), async (a, e) => guix(["--version"], a, e)),
		download: operation(
			Type.Object({ url: string }),
			async (a, e) => {
				const r = await guix(["download", "--", a.url], a, e);
				const lines = r.ok && !r.truncated ? r.stdout.split("\n").filter(Boolean) : [];
				return {
					...r,
					store_path: lines.find((line) => line.startsWith("/gnu/store/")) ?? null,
					hash: lines.findLast((line) => !line.startsWith("/")) ?? null,
				};
			},
			"url",
			true,
		),
		lint: operation(
			Type.Object({
				packages: strings,
				load_paths: optional(strings),
				checkers: optional(strings),
			}),
			async (a, e) =>
				guix(
					[
						"lint",
						...(a.load_paths ?? []).flatMap((p) => ["-L", p]),
						...(a.checkers?.length ? [`--checkers=${a.checkers.join(",")}`] : []),
						"--",
						...a.packages,
					],
					a,
					e,
				),
			"packages",
		),
		style: operation(
			Type.Object({
				packages: optional(strings),
				files: optional(strings),
				load_paths: optional(strings),
				styling: optional(string),
				dry_run: optional(Type.Boolean()),
			}),
			async (a, e) => {
				if (Boolean(a.packages?.length) === Boolean(a.files?.length))
					throw new Error("Give packages OR files");
				return guix(
					[
						"style",
						...(a.load_paths ?? []).flatMap((p) => ["-L", p]),
						...(a.styling ? [`--styling=${a.styling}`] : []),
						...(a.dry_run ? ["--dry-run"] : []),
						...(a.files?.length ? ["--whole-file"] : []),
						"--",
						...(a.files ?? a.packages ?? []),
					],
					a,
					e,
				);
			},
			undefined,
			true,
		),
		run: operation(
			Type.Object({ args: optional(strings) }),
			async (a, e) => guix(a.args ?? [], a, e),
			undefined,
			true,
		),
	},
};
export const makeDomain: Domain = {
	id: "make",
	namespace: "pi.make",
	alias: "make",
	description: "Make targets and noninteractive builds. Prefer repo actions for this repository.",
	operations: {
		run: operation(
			Type.Object({
				targets: optional(strings),
				args: optional(strings),
				file: optional(string),
				jobs: optional(Type.Integer({ minimum: 1, maximum: 256 })),
				variables: optional(
					Type.Record(Type.String({ pattern: "^[A-Za-z_][A-Za-z0-9_]*$" }), string),
				),
			}),
			async (a, e) =>
				command(
					"make",
					[
						"--no-print-directory",
						...(a.file ? ["-f", a.file] : []),
						...(a.jobs ? [`-j${a.jobs}`] : []),
						...(a.args ?? []),
						...Object.entries(a.variables ?? {}).map(([k, v]) => `${k}=${v}`),
						"--",
						...(a.targets ?? []),
					],
					a,
					e,
				),
			"targets",
			true,
		),
		"dry-run": operation(
			Type.Object({ targets: optional(strings), file: optional(string) }),
			async (a, e) =>
				command(
					"make",
					[
						"--no-print-directory",
						"--dry-run",
						...(a.file ? ["-f", a.file] : []),
						"--",
						...(a.targets ?? []),
					],
					a,
					e,
				),
			"targets",
		),
		targets: operation(Type.Object({ file: optional(string) }), async (a, e) => {
			const r = await command(
				"make",
				["--no-print-directory", "-rRqp", ...(a.file ? ["-f", a.file] : [])],
				a,
				e,
			);
			const text = complete({
				...r,
				ok: (r.exit_code === 0 || r.exit_code === 1) && !r.signal && !r.timed_out,
			});
			const section = text.split("# Files\n")[1]?.split("# files hash-table stats:")[0];
			if (section === undefined) throw new Error("Unsupported Make database format");
			const items = [
				...new Set(
					section
						.split("\n\n")
						.filter((block) => !block.includes("# Not a target:"))
						.flatMap((block) => block.split("\n"))
						.filter(
							(line) => /^[^#\s:=]+\s*:/.test(line) && !line.startsWith(".") && !line.includes("%"),
						)
						.map((line) => line.slice(0, line.indexOf(":"))),
				),
			].sort();
			return { ...r, ok: true, ...collection(items, 1000), stdout: undefined };
		}),
		version: operation(Type.Object({}), async (a, e) => command("make", ["--version"], a, e)),
	},
};
const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../../..");
const actions = {
	check: "check",
	"system-build": "build",
	"home-build": "home-build",
	"pi-sci-build": "pi-sci",
	"pi-sci-test": "pi-sci-test",
	"dusk-build": "dusk",
	"dusk-test": "dusk-test",
};
async function repoRoot(a: Options, e: Execution) {
	const cwd = resolve(e.ctx.cwd, a.cwd ?? ".");
	const sub = relative(root, cwd);
	if (sub.startsWith("..") || isAbsolute(sub))
		throw new Error("Repo capabilities require this Guix checkout");
	const pkg = JSON.parse(
		await readFile(resolve(root, "desktop/build/pi-sci/package.json"), "utf8"),
	);
	if (pkg.name !== "pi-sci-codemode") throw new Error("Repository identity mismatch");
	await access(resolve(root, "Makefile"));
	return root;
}
export const repoDomain: Domain = {
	id: "repo",
	namespace: "pi.repo",
	alias: "repo",
	description:
		"Explicit actions for this Guix checkout. No activation, stow, pull, or ambiguous build action.",
	operations: Object.fromEntries(
		Object.entries(actions).map(([name, target]) => [
			name,
			operation(
				Type.Object({
					dry_run: optional(Type.Boolean()),
					jobs: optional(Type.Integer({ minimum: 1, maximum: 256 })),
				}),
				async (a, e) => {
					const cwd = await repoRoot(a, e);
					return {
						...(await command(
							"make",
							[
								"--no-print-directory",
								...(a.dry_run ? ["--dry-run"] : []),
								...(a.jobs ? [`-j${a.jobs}`] : []),
								"--",
								target,
							],
							{ ...a, cwd },
							e,
						)),
						action: name,
						target,
					};
				},
				undefined,
				true,
			),
		]),
	),
};

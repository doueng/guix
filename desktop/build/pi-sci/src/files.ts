import { lstat, opendir, readdir } from "node:fs/promises";
import { join, resolve } from "node:path";
import { Type } from "typebox";
import {
	collection,
	type Domain,
	type Execution,
	limit,
	type Options,
	operation,
	string,
	strings,
} from "./operations.ts";
import { readJson, readJsonl } from "./structured-files.ts";

const optional = Type.Optional;
const GLOB_DIRECTORIES = 10_000;
const GLOB_MATCHES = 100_000;
const USAGE_ENTRIES = 200_000;
const SORT_SCAN = 10_000;

function kind(entry: { isSymbolicLink(): boolean; isDirectory(): boolean; isFile(): boolean }) {
	return entry.isSymbolicLink()
		? "symlink"
		: entry.isDirectory()
			? "directory"
			: entry.isFile()
				? "file"
				: "other";
}
function segmentPattern(segment: string) {
	let source = "";
	for (let i = 0; i < segment.length; i++) {
		const c = segment[i];
		const close = c === "[" ? segment.indexOf("]", i + 2) : -1;
		if (c === "*") source += "[^/]*";
		else if (c === "?") source += "[^/]";
		else if (close !== -1) {
			const body = segment.slice(i + 1, close).replace(/\\/g, "\\\\");
			source += `[${body.startsWith("!") ? `^${body.slice(1)}` : body}]`;
			i = close;
		} else source += c.replace(/[.+^${}()|[\]\\]/g, "\\$&");
	}
	return new RegExp(`^${source}$`);
}
async function children(directory: string) {
	try {
		return await readdir(directory, { withFileTypes: true });
	} catch {
		return [];
	}
}
async function expandGlob(pattern: string, signal?: AbortSignal) {
	let paths = ["/"],
		visited = 0,
		truncated = false;
	const visit = () => {
		signal?.throwIfAborted();
		if (++visited > GLOB_DIRECTORIES) truncated = true;
		return !truncated;
	};
	for (const segment of pattern.split("/").filter(Boolean)) {
		const next: string[] = [];
		if (segment === "**") {
			const queue = [...paths];
			while (queue.length && visit()) {
				const directory = queue.shift() as string;
				next.push(directory);
				for (const entry of await children(directory))
					if (entry.isDirectory() && !entry.name.startsWith("."))
						queue.push(join(directory, entry.name));
			}
			truncated ||= queue.length > 0;
		} else if (!/[*?[]/.test(segment)) {
			for (const directory of paths) next.push(join(directory, segment));
		} else {
			const matcher = segmentPattern(segment);
			for (const directory of paths) {
				if (!visit()) break;
				for (const entry of await children(directory))
					if (matcher.test(entry.name) && (segment.startsWith(".") || !entry.name.startsWith(".")))
						next.push(join(directory, entry.name));
				if (next.length > GLOB_MATCHES) {
					truncated = true;
					break;
				}
			}
		}
		paths = [...new Set(next)];
	}
	const existing = [];
	for (const candidate of paths) {
		signal?.throwIfAborted();
		try {
			await lstat(candidate);
			existing.push(candidate);
		} catch {}
	}
	return { paths: existing.sort(), truncated };
}
async function diskUsage(root: string, signal?: AbortSignal) {
	const queue = [root];
	let bytes = 0,
		disk = 0,
		files = 0,
		directories = 0,
		entries = 0;
	while (queue.length) {
		signal?.throwIfAborted();
		const current = queue.shift() as string;
		const s = await lstat(current);
		bytes += s.size;
		disk += s.blocks * 512;
		if (++entries > USAGE_ENTRIES) break;
		if (s.isDirectory()) {
			directories++;
			for (const entry of await children(current)) queue.push(join(current, entry.name));
		} else files++;
	}
	return {
		path: root,
		bytes,
		disk_bytes: disk,
		files,
		directories,
		truncated: queue.length > 0,
	};
}
function path(a: Options & { path?: string }, e: Execution) {
	return resolve(e.ctx.cwd, a.cwd ?? ".", a.path ?? ".");
}
async function factory(name: "grep" | "find" | "read") {
	const module = await import(new URL(`../dist/pi-${name}.mjs`, import.meta.url).href);
	return module[`create${name[0].toUpperCase() + name.slice(1)}ToolDefinition`];
}
async function piSearch(
	name: "grep" | "find",
	input: Record<string, unknown>,
	e: Execution,
	cwd: string,
) {
	const create = await factory(name);
	const tool = create(cwd);
	const result = await tool
		.execute("sci-search", input, e.signal, undefined, { ...e.ctx, cwd })
		.catch((error: unknown) => {
			if (error instanceof Error && /regex parse error/.test(error.message))
				throw new Error(
					`${error.message}\nHint: pass :literal true to search for the exact string.`,
				);
			throw error;
		});
	if (result.isError) throw new Error("Pi search failed");
	if (!result.structuredContent)
		throw new Error("Pi search adapter did not return structured records");
	return result.structuredContent as Record<string, unknown>;
}
async function search(
	name: "grep" | "find",
	input: Record<string, unknown>,
	a: Options & { path?: string; paths?: string[]; limit?: number },
	e: Execution,
) {
	if (a.path && a.paths) throw new Error("Use path OR paths");
	const paths = a.paths ?? [a.path ?? "."];
	if (!paths.length) throw new Error("At least one search path is required");
	const cwd = resolve(e.ctx.cwd, a.cwd ?? "."),
		count = a.limit ?? 100;
	const items: unknown[] = [];
	let truncated = false;
	for (const p of paths) {
		e.signal?.throwIfAborted();
		const r = await piSearch(name, { ...input, path: p, limit: count }, e, cwd);
		const rows = r.items as unknown[];
		items.push(
			...(name === "find" && a.paths
				? rows.map((item) => resolve(String(r.root), String(item)))
				: rows),
		);
		truncated ||= Boolean(r.truncated);
		if (items.length >= count) {
			truncated ||= p !== paths.at(-1);
			break;
		}
	}
	const bounded = collection(items, count);
	return {
		...bounded,
		truncated: bounded.truncated || truncated,
		root: a.paths ? cwd : resolve(cwd, a.path ?? "."),
		paths,
	};
}
export const searchDomain: Domain = {
	local: true,
	id: "search",
	namespace: "pi.search",
	alias: "search",
	description:
		"Pi file-content and filename search. No rg capability. Results have items, root, and truncated; columns are one-based UTF-8 byte offsets.",
	operations: {
		text: operation(
			Type.Object({
				query: string,
				path: optional(string),
				paths: optional(strings),
				glob: optional(string),
				literal: optional(Type.Boolean()),
				ignore_case: optional(Type.Boolean()),
				limit,
			}),
			async (a, e) =>
				search(
					"grep",
					{
						pattern: a.query,
						path: a.path,
						glob: a.glob,
						literal: a.literal,
						ignoreCase: a.ignore_case,
						limit: a.limit,
					},
					a,
					e,
				),
			"query",
		),
		files: operation(
			Type.Object({ glob: string, path: optional(string), paths: optional(strings), limit }),
			async (a, e) => search("find", { pattern: a.glob, path: a.path, limit: a.limit }, a, e),
			"glob",
		),
	},
};
export const fsDomain: Domain = {
	local: true,
	id: "fs",
	namespace: "pi.fs",
	alias: "fs",
	description:
		"Bounded text/image reads via Pi, complete JSON reads, projected JSONL pages, non-following directory listing, and filesystem metadata. JSON reads reject incomplete input. JSONL pages return next_cursor and per-record value_truncated flags.",
	operations: {
		read: operation(
			Type.Object({
				path: string,
				start_line: optional(Type.Integer({ minimum: 1 })),
				end_line: optional(Type.Integer({ minimum: 1 })),
				limit: optional(Type.Integer({ minimum: 1, maximum: 2000 })),
			}),
			async (a, e) => {
				if (a.end_line !== undefined && a.limit !== undefined)
					throw new Error("Use end_line OR limit");
				const start = a.start_line ?? 1;
				if (a.end_line !== undefined && a.end_line < start)
					throw new Error("end_line precedes start_line");
				const create = await factory("read");
				const cwd = resolve(e.ctx.cwd, a.cwd ?? ".");
				const r = await create(cwd).execute(
					"sci-read",
					{
						path: a.path,
						offset: start,
						limit: a.end_line === undefined ? a.limit : a.end_line - start + 1,
					},
					e.signal,
					undefined,
					{ ...e.ctx, cwd },
				);
				if (!r.structuredContent) throw new Error("Pi read adapter returned no data");
				return r.structuredContent as Record<string, unknown>;
			},
			"path",
		),
		"read-json": operation(
			Type.Object({ path: string }),
			async (a, e) => readJson(path(a, e), e.signal),
			"path",
		),
		"read-jsonl": operation(
			Type.Object({
				path: string,
				cursor: optional(
					Type.Object(
						{
							offset: Type.Integer({ minimum: 0, maximum: Number.MAX_SAFE_INTEGER }),
							line: Type.Integer({ minimum: 1, maximum: Number.MAX_SAFE_INTEGER }),
						},
						{ additionalProperties: false },
					),
				),
				fields: optional(
					Type.Record(
						Type.String({ minLength: 1, maxLength: 128 }),
						Type.Array(Type.String({ maxLength: 256 }), { minItems: 1, maxItems: 16 }),
						{ maxProperties: 32 },
					),
				),
				limit,
				max_string_chars: optional(Type.Integer({ minimum: 0, maximum: 16384 })),
			}),
			async (a, e) => readJsonl(path(a, e), a, e.signal),
			"path",
		),
		stat: operation(
			Type.Object({ path: string }),
			async (a, e) => {
				const p = path(a, e);
				e.signal?.throwIfAborted();
				const s = await lstat(p);
				e.signal?.throwIfAborted();
				return {
					path: p,
					kind: kind(s),
					size: s.size,
					mode: s.mode,
					modified_ms: s.mtimeMs,
				};
			},
			"path",
		),
		"exists?": operation(
			Type.Object({ path: string }),
			async (a, e) => {
				e.signal?.throwIfAborted();
				try {
					await lstat(path(a, e));
					e.signal?.throwIfAborted();
					return { exists: true };
				} catch (error) {
					if (["ENOENT", "ENOTDIR"].includes((error as NodeJS.ErrnoException).code ?? ""))
						return { exists: false };
					throw error;
				}
			},
			"path",
			false,
			"exists",
		),
		glob: operation(
			Type.Object({ pattern: string, limit }),
			async (a, e) => {
				const pattern = resolve(e.ctx.cwd, a.cwd ?? ".", a.pattern);
				const { paths, truncated } = await expandGlob(pattern, e.signal);
				const bounded = collection(paths, a.limit ?? 100);
				return { ...bounded, pattern, truncated: bounded.truncated || truncated };
			},
			"pattern",
		),
		"disk-usage": operation(
			Type.Object({ path: optional(string) }),
			async (a, e) => diskUsage(path(a, e), e.signal),
			"path",
		),
		list: operation(
			Type.Object({
				path: optional(string),
				depth: optional(Type.Integer({ minimum: 1, maximum: 16 })),
				limit,
				stat: optional(Type.Boolean()),
				sort: optional(
					Type.Union([Type.Literal("name"), Type.Literal("modified"), Type.Literal("size")]),
				),
			}),
			async (a, e) => {
				const root = path(a, e),
					count = a.sort ? SORT_SCAN : (a.limit ?? 100),
					queue = [{ path: root, depth: 1 }];
				const items: Record<string, unknown>[] = [];
				let visited = 0;
				while (queue.length && items.length <= count) {
					e.signal?.throwIfAborted();
					if (++visited > 1000) break;
					const directory = queue.shift();
					if (!directory) break;
					const dir = await opendir(directory.path);
					for await (const entry of dir) {
						e.signal?.throwIfAborted();
						const p = join(directory.path, entry.name);
						items.push({
							path: p,
							name: entry.name,
							kind: kind(entry),
							depth: directory.depth,
						});
						if (entry.isDirectory() && directory.depth < (a.depth ?? 1))
							queue.push({ path: p, depth: directory.depth + 1 });
						if (items.length > count) break;
					}
				}
				const scanned = items.length > count || queue.length > 0;
				if (a.stat || a.sort)
					for (const item of items) {
						e.signal?.throwIfAborted();
						const s = await lstat(item.path as string);
						Object.assign(item, { size: s.size, modified_ms: s.mtimeMs });
					}
				const order = {
					name: (x: Record<string, unknown>, y: Record<string, unknown>) =>
						String(x.path).localeCompare(String(y.path)),
					modified: (x: Record<string, unknown>, y: Record<string, unknown>) =>
						Number(y.modified_ms) - Number(x.modified_ms),
					size: (x: Record<string, unknown>, y: Record<string, unknown>) =>
						Number(y.size) - Number(x.size),
				};
				if (a.sort) items.sort(order[a.sort]);
				const bounded = collection(items, a.limit ?? 100);
				return { ...bounded, root, truncated: bounded.truncated || scanned };
			},
		),
	},
};

import { lstat, opendir } from "node:fs/promises";
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
	const result = await tool.execute("sci-search", input, e.signal, undefined, { ...e.ctx, cwd });
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
					kind: s.isSymbolicLink()
						? "symlink"
						: s.isDirectory()
							? "directory"
							: s.isFile()
								? "file"
								: "other",
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
		list: operation(
			Type.Object({
				path: optional(string),
				depth: optional(Type.Integer({ minimum: 1, maximum: 16 })),
				limit,
			}),
			async (a, e) => {
				const root = path(a, e),
					count = a.limit ?? 100,
					queue = [{ path: root, depth: 1 }];
				const items: Record<string, unknown>[] = [];
				let visited = 0;
				while (queue.length && items.length <= count) {
					e.signal?.throwIfAborted();
					if (++visited > 1000) return { ...collection(items, count), root, truncated: true };
					const directory = queue.shift();
					if (!directory) break;
					const dir = await opendir(directory.path);
					for await (const entry of dir) {
						e.signal?.throwIfAborted();
						const p = join(directory.path, entry.name);
						items.push({
							path: p,
							name: entry.name,
							kind: entry.isSymbolicLink()
								? "symlink"
								: entry.isDirectory()
									? "directory"
									: entry.isFile()
										? "file"
										: "other",
							depth: directory.depth,
						});
						if (entry.isDirectory() && directory.depth < (a.depth ?? 1))
							queue.push({ path: p, depth: directory.depth + 1 });
						if (items.length > count) break;
					}
				}
				return {
					...collection(items, count),
					root,
					truncated: collection(items, count).truncated || queue.length > 0,
				};
			},
		),
	},
};

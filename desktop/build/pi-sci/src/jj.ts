import { Type } from "typebox";
import {
	collection,
	command,
	complete,
	type Domain,
	type Execution,
	limit,
	type Options,
	operation,
	string,
	strings,
} from "./operations.ts";

const commitTemplate = `'{' ++ '"commit":' ++ json(self) ++ ',"empty":' ++ json(empty) ++ ',"conflict":' ++ json(conflict) ++ "}\\n"`;
const diffTemplate = `'{' ++ '"path":' ++ json(path) ++ ',"status":' ++ json(status) ++ ',"source":' ++ json(source.path()) ++ ',"before":' ++ json(source.file_type()) ++ ',"after":' ++ json(target.file_type()) ++ "}\\n"`;
const optional = Type.Optional;
const revision = { revision: optional(string) };
const diffInput = {
	revisions: optional(string),
	from: optional(string),
	to: optional(string),
	paths: optional(strings),
	limit,
};
async function jj(argv: string[], args: Options, execution: Execution) {
	return command("jj", ["--no-pager", "--color=never", ...argv], args, execution);
}
function records(stdout: string): Record<string, unknown>[] {
	return stdout
		.split("\n")
		.filter(Boolean)
		.map((line) => {
			const value = JSON.parse(line);
			if (!value || typeof value !== "object" || Array.isArray(value))
				throw new Error("Invalid JJ JSON record");
			return value;
		});
}
async function commits(
	args: Options & { revisions?: string; paths?: string[]; limit?: number },
	execution: Execution,
) {
	const count = args.limit ?? 100;
	const result = await jj(
		[
			"log",
			"--no-graph",
			"--limit",
			String(count + 1),
			"-r",
			args.revisions ?? "@",
			"-T",
			commitTemplate,
			"--",
			...(args.paths ?? []).map((p) => `cwd:${JSON.stringify(p)}`),
		],
		args,
		execution,
	);
	const items = records(complete(result)).map((row) => {
		const c = row.commit as Record<string, unknown>;
		if (
			typeof c?.commit_id !== "string" ||
			typeof c.change_id !== "string" ||
			typeof c.description !== "string" ||
			typeof row.empty !== "boolean" ||
			typeof row.conflict !== "boolean"
		)
			throw new Error("Unsupported JJ commit JSON format");
		return {
			"commit-id": c.commit_id,
			"change-id": c.change_id,
			description: c.description,
			author: c.author,
			committer: c.committer,
			parents: c.parents,
			"empty?": row.empty,
			"conflict?": row.conflict,
		};
	});
	return { ...result, ...collection(items, count), stdout: undefined };
}
function diffFlags(
	args: { revisions?: string; from?: string; to?: string; paths?: string[] },
	format: string[],
) {
	if (args.revisions && (args.from || args.to))
		throw new Error("Use revisions OR from/to, not both");
	return [
		...(args.revisions
			? ["-r", args.revisions]
			: [...(args.from ? ["--from", args.from] : []), ...(args.to ? ["--to", args.to] : [])]),
		...format,
		"--",
		...(args.paths ?? []).map((p) => `cwd:${JSON.stringify(p)}`),
	];
}
async function changes(
	args: Options & {
		revisions?: string;
		from?: string;
		to?: string;
		paths?: string[];
		limit?: number;
	},
	execution: Execution,
) {
	const result = await jj(["diff", ...diffFlags(args, ["-T", diffTemplate])], args, execution);
	const items = records(complete(result));
	for (const item of items)
		if (typeof item.path !== "string" || typeof item.status !== "string")
			throw new Error("Unsupported JJ diff JSON format");
	return { ...result, ...collection(items, args.limit), stdout: undefined };
}
export const jjDomain: Domain = {
	id: "jj",
	namespace: "pi.jj",
	alias: "jj",
	description:
		"Jujutsu source control. Reads return structured commit and file records; mutations are explicit. No git namespace.",
	operations: {
		status: operation(Type.Object({ limit }), async (a, e) => {
			const working = await commits({ ...a, revisions: "@", limit: 1 }, e);
			const head = working.items[0] as Record<string, unknown> | undefined;
			if (!head) throw new Error("JJ working copy was not resolved");
			const diff = await changes({ ...a, revisions: String(head["commit-id"]) }, e);
			return { ...diff, working_copy: head };
		}),
		log: operation(
			Type.Object({ revisions: optional(string), limit, paths: optional(strings) }),
			commits,
		),
		show: operation(
			Type.Object({ ...revision }),
			async (a, e) => commits({ ...a, revisions: a.revision ?? "@", limit: 1 }, e),
			"revision",
		),
		diff: operation(Type.Object(diffInput), changes),
		patch: operation(
			Type.Object({
				revisions: optional(string),
				from: optional(string),
				to: optional(string),
				paths: optional(strings),
				stat: optional(Type.Boolean()),
			}),
			async (a, e) => jj(["diff", ...diffFlags(a, [a.stat ? "--stat" : "--git"])], a, e),
		),
		"files-changed": operation(Type.Object(diffInput), async (a, e) => {
			const r = await changes(a, e);
			return { ...r, items: r.items.map((row) => (row as Record<string, unknown>).path) };
		}),
		"file-show": operation(Type.Object({ path: string, ...revision }), async (a, e) => {
			const r = await jj(
				["file", "show", "-r", a.revision ?? "@", "--", `file:${JSON.stringify(a.path)}`],
				a,
				e,
			);
			return { ...r, path: a.path, text: r.stdout };
		}),
		"bookmark-list": operation(Type.Object({ limit }), async (a, e) => {
			const r = await jj(["bookmark", "list", "-T", 'json(self) ++ "\\n"'], a, e);
			return { ...r, ...collection(records(complete(r)), a.limit), stdout: undefined };
		}),
		"op-log": operation(Type.Object({ limit }), async (a, e) => {
			const count = a.limit ?? 20;
			const r = await jj(
				["op", "log", "--no-graph", "--limit", String(count + 1), "-T", 'json(self) ++ "\\n"'],
				a,
				e,
			);
			return { ...r, ...collection(records(complete(r)), count), stdout: undefined };
		}),
		version: operation(Type.Object({}), async (a, e) => jj(["--version"], a, e)),
		new: operation(
			Type.Object({ revisions: optional(strings), message: optional(string) }),
			async (a, e) =>
				jj(["new", ...(a.message ? ["-m", a.message] : []), "--", ...(a.revisions ?? ["@"])], a, e),
			undefined,
			true,
		),
		describe: operation(
			Type.Object({ message: string, ...revision }),
			async (a, e) => jj(["describe", "-r", a.revision ?? "@", "-m", a.message], a, e),
			"message",
			true,
		),
		squash: operation(
			Type.Object({
				from: optional(string),
				into: optional(string),
				message: optional(string),
				use_destination_message: optional(Type.Boolean()),
			}),
			async (a, e) => {
				if ((a.message === undefined) === !a.use_destination_message)
					throw new Error("Pass :message or :use_destination_message true, not both");
				return jj(
					[
						"squash",
						...(a.from ? ["--from", a.from] : []),
						...(a.into ? ["--into", a.into] : []),
						...(a.message === undefined ? ["--use-destination-message"] : ["-m", a.message]),
					],
					a,
					e,
				);
			},
			undefined,
			true,
		),
		rebase: operation(
			Type.Object({ source: string, destination: string }),
			async (a, e) => jj(["rebase", "-s", a.source, "-d", a.destination], a, e),
			undefined,
			true,
		),
		run: operation(
			Type.Object({ args: optional(strings) }),
			async (a, e) => jj(a.args ?? [], a, e),
			undefined,
			true,
		),
	},
};

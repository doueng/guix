import { resolve } from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { type Static, type TObject, Type } from "typebox";
import { type CommandResult, runCommand } from "./command-process.ts";

export const string = Type.String({ maxLength: 65536, pattern: "^[^\\u0000]*$" });
export const strings = Type.Array(string, { maxItems: 256 });
export const limit = Type.Optional(Type.Integer({ minimum: 1, maximum: 1000 }));
export const common = {
	cwd: Type.Optional(Type.String({ minLength: 1, maxLength: 4096, pattern: "^[^\\u0000]*$" })),
	env: Type.Optional(Type.Record(Type.String({ pattern: "^[^=\\u0000]+$" }), string)),
	timeout_ms: Type.Optional(Type.Integer({ minimum: 1, maximum: 2147483647 })),
};
export type Options = { cwd?: string; env?: Record<string, string>; timeout_ms?: number };
export type Execution = { ctx: ExtensionContext; signal?: AbortSignal };
export type Data = object;
type Json = string | number | boolean | null | Json[] | { [key: string]: Json };
export function operation<T extends TObject>(
	input: T,
	run: (args: Static<T> & Options, execution: Execution) => Promise<Data>,
	positional?: string,
	mutation = false,
	select?: string,
) {
	return {
		input,
		run: (args: Record<string, unknown>, execution: Execution) =>
			run(args as Static<T> & Options, execution),
		positional,
		mutation,
		select,
	};
}
export type Operation = ReturnType<typeof operation>;
export type Domain = {
	id: string;
	namespace: string;
	alias: string;
	description: string;
	operations: Record<string, Operation>;
	local?: boolean;
};
export function schema(domain: Domain) {
	const variants = Object.entries(domain.operations).map(([name, op]) =>
		Type.Object(
			{
				operation: Type.Literal(name),
				...(domain.local ? { cwd: common.cwd, timeout_ms: common.timeout_ms } : common),
				...op.input.properties,
			},
			{ additionalProperties: false },
		),
	);
	if (!variants.length) throw new Error("A domain must have operations");
	return Type.Union(variants as unknown as [TObject, ...TObject[]]);
}
export function description(domain: Domain) {
	return `${domain.description} SCI functions: ${Object.entries(domain.operations)
		.map(
			([name, op]) =>
				`(${domain.alias}/${name} {${Object.keys(op.input.properties)
					.map((key) => `:${key} ...`)
					.join(" ")}})${op.mutation ? " [mutation]" : ""}`,
		)
		.join(
			"; ",
		)}. Await calls. Named operations return structured data with explicit truncation. Run programs on the host; write programs in SCI. Prefer semantic operations over raw arguments. Irreversible operations require permission.`;
}
export function registerDomain(pi: ExtensionAPI, domain: Domain) {
	pi.registerTool({
		name: domain.id,
		label: domain.alias,
		exposure: "codemode",
		description: description(domain),
		namespace: { name: domain.alias, description: domain.description },
		parameters: schema(domain),
		outputSchema: Type.Record(Type.String(), Type.Unknown()),
		execute: async (_id, args, signal, _update, ctx) => {
			const op = domain.operations[args.operation as string];
			if (!op) throw new Error("Unknown domain operation");
			const controller = new AbortController();
			const abort = () => controller.abort(signal?.reason);
			signal?.addEventListener("abort", abort, { once: true });
			if (signal?.aborted) abort();
			const timeout =
				domain.local && typeof args.timeout_ms === "number"
					? setTimeout(
							() => controller.abort(new Error("Domain operation deadline exceeded")),
							args.timeout_ms,
						)
					: undefined;
			let data: { [key: string]: Json };
			try {
				controller.signal.throwIfAborted();
				data = JSON.parse(JSON.stringify(await op.run(args, { ctx, signal: controller.signal })));
				controller.signal.throwIfAborted();
			} catch (error) {
				if (controller.signal.aborted) throw controller.signal.reason;
				throw error;
			} finally {
				if (timeout) clearTimeout(timeout);
				signal?.removeEventListener("abort", abort);
			}
			return {
				content: [{ type: "text", text: JSON.stringify(data) }],
				details: data,
				structuredContent: data,
			};
		},
	});
}
export function bindings(domain: Domain) {
	return {
		id: domain.id,
		namespace: domain.namespace,
		alias: domain.alias,
		functions: Object.fromEntries(
			Object.entries(domain.operations).map(([name, op]) => [
				name,
				{ operation: name, positional: op.positional ?? null, select: op.select ?? null },
			]),
		),
	};
}
export async function command(
	program: string,
	argv: string[],
	args: Options,
	execution: Execution,
): Promise<CommandResult & { ok: boolean; duration_ms: number }> {
	const start = performance.now();
	const result = await runCommand(program, argv, {
		cwd: resolve(execution.ctx.cwd, args.cwd ?? "."),
		env: args.env,
		timeout_ms: args.timeout_ms,
		signal: execution.signal,
	});
	return {
		...result,
		ok: result.exit_code === 0 && !result.signal && !result.timed_out,
		duration_ms: Math.round(performance.now() - start),
	};
}
export function complete(result: CommandResult & { ok: boolean }) {
	if (!result.ok || result.truncated)
		throw new Error(
			`Cannot normalize incomplete command output (exit ${result.exit_code}, timed out ${result.timed_out}, truncated ${result.truncated}). ${result.stderr.slice(0, 2000)} ${result.full_output_path ?? ""}`,
		);
	return result.stdout;
}
export function collection(items: unknown[], count = 100) {
	let bytes = 0;
	const kept = [];
	for (const item of items.slice(0, count)) {
		const size = Buffer.byteLength(JSON.stringify(item));
		if (bytes + size > 48 * 1024) break;
		kept.push(item);
		bytes += size;
	}
	if (items.length && !kept.length)
		throw new Error("One record exceeds the structured output limit; refine the query");
	return { items: kept, truncated: kept.length < items.length };
}

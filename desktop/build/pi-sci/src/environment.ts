import { createHash } from "node:crypto";
import type { SessionEntry } from "@earendil-works/pi-coding-agent";

export const ENVIRONMENT_RUNTIME = "sci-5fe89056384418be50d232b4278344cb3a1a526b-library-v1";
export const ENVIRONMENT_KEY = "__pi_sci/environment-delta";
export const MAX_ENVIRONMENT_OPERATIONS = 512;
export const MAX_ENVIRONMENT_SOURCE_CHARS = 128 * 1024;
export const MAX_DEFINITION_SOURCE_CHARS = 16 * 1024;

export interface Definition {
	op: "define";
	name: string;
	source: string;
	kind: "function" | "value";
	parameters: string | null;
	async: boolean;
}
export interface Forget {
	op: "forget";
	name: string;
}
export type EnvironmentOperation = Definition | Forget;
export interface EnvironmentDelta {
	version: 1;
	runtime: string;
	reset: boolean;
	operations: EnvironmentOperation[];
}
export interface Environment {
	revision: string;
	operations: EnvironmentOperation[];
}

function record(value: unknown): value is Record<string, unknown> {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}
function name(value: unknown): value is string {
	return (
		typeof value === "string" &&
		value.length <= 128 &&
		/^[A-Za-z_][A-Za-z0-9_!?*+<>=$%.-]*$/.test(value) &&
		![
			"definitions",
			"source",
			"forget",
			"text",
			"println",
			"prn",
			"image",
			"exit",
			"store",
			"load",
			"unstore",
			"all",
			"all-settled",
		].includes(value)
	);
}

export function parseEnvironmentDelta(value: unknown): EnvironmentDelta {
	if (
		!record(value) ||
		value.version !== 1 ||
		value.runtime !== ENVIRONMENT_RUNTIME ||
		typeof value.reset !== "boolean" ||
		!Array.isArray(value.operations)
	) {
		throw new Error(
			"Incompatible SCI library journal. Inspect the session or use /sci-library reset.",
		);
	}
	const operations: EnvironmentOperation[] = value.operations.map((item: unknown) => {
		if (!record(item) || !name(item.name)) throw new Error("Invalid SCI library declaration name.");
		if (item.op === "forget") return { op: "forget", name: item.name };
		if (
			item.op !== "define" ||
			typeof item.source !== "string" ||
			!item.source.trim() ||
			item.source.length > MAX_DEFINITION_SOURCE_CHARS ||
			!(item.kind === "function" || item.kind === "value") ||
			typeof item.async !== "boolean" ||
			!(item.parameters === null || typeof item.parameters === "string") ||
			(item.kind === "value" && (item.async || item.parameters !== null)) ||
			(item.kind === "function" && item.parameters === null)
		) {
			throw new Error("Invalid SCI library declaration.");
		}
		return {
			op: "define",
			name: item.name,
			source: item.source,
			kind: item.kind === "function" ? "function" : "value",
			parameters: item.parameters,
			async: item.async,
		};
	});
	assertEnvironmentLimits(operations);
	return { version: 1, runtime: ENVIRONMENT_RUNTIME, reset: value.reset, operations };
}

export function assertEnvironmentLimits(operations: readonly EnvironmentOperation[]) {
	if (
		operations.length > MAX_ENVIRONMENT_OPERATIONS ||
		operations.reduce((sum, op) => sum + (op.op === "define" ? op.source.length : 0), 0) >
			MAX_ENVIRONMENT_SOURCE_CHARS
	) {
		throw new Error(
			"SCI library limit exceeded. Use /sci-library reset or branch to an earlier environment.",
		);
	}
}

export function readEnvironment(branch: readonly SessionEntry[]): Environment {
	const entries = branch.filter(
		(entry) =>
			entry.type === "custom" &&
			entry.customType === "codemode-store" &&
			record(entry.data) &&
			entry.data.sciEnvironment !== undefined,
	);
	let begin = 0;
	for (let index = entries.length - 1; index >= 0; index--) {
		const entry = entries[index];
		if (
			entry?.type === "custom" &&
			record(entry.data) &&
			record(entry.data.sciEnvironment) &&
			entry.data.sciEnvironment.reset === true
		) {
			begin = index;
			break;
		}
	}
	const operations: EnvironmentOperation[] = [];
	const hash = createHash("sha256").update(ENVIRONMENT_RUNTIME);
	for (const entry of entries.slice(begin)) {
		if (entry.type !== "custom" || !record(entry.data)) continue;
		const delta = parseEnvironmentDelta(entry.data.sciEnvironment);
		operations.push(...delta.operations);
		hash.update(entry.id).update(JSON.stringify(delta));
	}
	assertEnvironmentLimits(operations);
	return { revision: hash.digest("hex"), operations };
}

export function environmentDefinitions(environment: Environment): Definition[] {
	const definitions = new Map<string, Definition>();
	for (const operation of environment.operations) {
		if (operation.op === "define") definitions.set(operation.name, operation);
		else definitions.delete(operation.name);
	}
	return [...definitions.values()].sort((a, b) => a.name.localeCompare(b.name));
}

export function environmentSummary(environment: Environment) {
	const definitions = environmentDefinitions(environment);
	const lines = definitions
		.slice(0, 8)
		.map(
			(item) =>
				`session/${item.name} ${(item.parameters ?? "[value]").slice(0, 120)}${item.async ? " ^:async" : ""}`,
		);
	return `Branch SCI library revision ${environment.revision.slice(0, 12)}. ${definitions.length} active definitions.\n${lines.join("\n").slice(0, 1100)}\nUse (session/definitions) and (session/source 'name) to inspect the branch library. Only defsession persists.`;
}

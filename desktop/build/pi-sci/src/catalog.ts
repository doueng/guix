import { fileURLToPath } from "node:url";
import { toCodemodeIdentifier } from "@earendil-works/pi-codemode";
import type { ToolLoadout, ToolNamespace } from "@earendil-works/pi-coding-agent";
import { DOMAINS } from "./commands.ts";
import { signatures } from "./operations.ts";

const STEERING: Record<string, string> = {
	bash: "Shell escape hatch. Prefer semantic jj, guix, repo, make, fs, sys, and search SCI functions. Use Pi-backed search rather than an rg process. Keep ordinary data analysis in SCI; do not generate temporary interpreter programs.",
	read: "Display-limited text for humans. Prefer fs/read, which returns :text, line metadata, :next_line, and image blocks.",
};

export const PROMPT_REWRITES: [string, string][] = [
	[
		"- Use bash for file operations like ls, rg, find",
		"- Use SCI fs and search functions for file operations (fs/read, fs/list, fs/glob, search/files, search/text); bash is an escape hatch",
	],
	[
		"- Use read to examine files instead of cat or sed.",
		"- Use fs/read to examine files instead of cat or sed.",
	],
];

export function rewritePrompt(prompt: string) {
	return PROMPT_REWRITES.reduce((text, [from, to]) => text.split(from).join(to), prompt);
}

export interface Capability {
	id: string;
	name: string;
	symbol: string;
	description: string;
	inputSchema: unknown;
	outputSchema?: unknown;
	namespace?: ToolNamespace;
}

export function capabilities(
	tools: ToolLoadout["callable"],
	namespace: (name: string) => ToolNamespace | undefined,
	guidelines: (name: string) => readonly string[] = () => [],
) {
	const names = new Set<string>();
	return tools
		.filter((tool) => tool.name !== "codemode")
		.map((tool) => {
			const name = toCodemodeIdentifier(tool.name);
			if (names.has(name)) throw new Error(`Codemode tool identifier collision: ${name}`);
			names.add(name);
			const bullets = guidelines(tool.name)
				.map((line) => line.trim())
				.filter(Boolean)
				.map((line) => `- ${line}`);
			return {
				id: tool.name,
				name,
				symbol: `tools/${name}`,
				description: [STEERING[tool.name] ?? "", tool.description, ...bullets]
					.filter(Boolean)
					.join("\n"),
				inputSchema: tool.parameters,
				outputSchema: tool.outputSchema,
				namespace: namespace(tool.name),
			} satisfies Capability;
		});
}

type Schema = {
	type?: string;
	items?: Schema;
	properties?: Record<string, Schema>;
	required?: string[];
};

function keys(schema: Schema): string {
	const required = new Set(schema.required ?? []);
	return Object.entries(schema.properties ?? {})
		.map(([key, value]) => {
			const nested =
				value.type === "array" && value.items?.properties ? ` [{${keys(value.items)}}]` : "";
			return `:${key}${required.has(key) ? "!" : ""}${nested}`;
		})
		.join(" ");
}

export function toolSignature(entry: Capability) {
	const options = keys(entry.inputSchema as Schema);
	return `(${entry.symbol}${options ? ` {${options}}` : ""})`;
}

function summary(entry: Capability) {
	const line = entry.description.split("\n")[0] ?? "";
	const sentence = line.match(/^.*?[.!?](?=\s|$)/)?.[0] ?? line;
	return `${entry.symbol}: ${sentence}`;
}

const README = fileURLToPath(new URL("../README.md", import.meta.url));

// Claude Code cuts MCP tool descriptions at 2048 characters. What a model needs
// to call functions without guessing keys must fit before that point.
export const DESCRIPTION_HEAD = 2048;
// Domains whose signatures come before the plain tools'. The rest follow in
// DOMAIN_ORDER while they fit.
const HEAD_DOMAINS = ["fs", "search", "sys", "jj"];
const DOMAIN_ORDER = [...HEAD_DOMAINS, "make", "repo", "guix"];

export const SCI_BASICS = `Run restricted Clojure with SCI inside Pi's QuickJS sandbox.
The input is raw Clojure source, not JSON or a code fence. The body is async; use (await ...) for tool, discovery, and model calls. Helper functions containing await need ^:async metadata.
Optional first line: ;; @options: {"max_output_tokens": 10000, "timeout_ms": 60000}
Read ${README} for the active SCI documentation.`;

function order(id: string) {
	const index = DOMAIN_ORDER.indexOf(id);
	return index < 0 ? DOMAIN_ORDER.length : index;
}

const SIGNATURE_HEADER =
	"Signatures. The positional argument comes first, then an options map; ! marks a required key. Every call also accepts :cwd and :timeout_ms; command domains accept :env.";

const MORE_SIGNATURES =
	'Signatures not listed here follow later. (catalog/describe "id") returns any tool\'s full schema.';

export const SCI_INTRO = `Workflow guidance lives in AGENTS.md; this section lists runtime facts.
No JavaScript interop, arbitrary require, eval, filesystem, process, network, or timers. Effects go through Pi's injected capabilities. require forms must precede executable forms and can only select provided namespaces.
Strings print directly. Command and bash results print as an [exit ...] status line followed by raw output. Other collections print as EDN; use pr-str to see a command result as data. Each text block keeps a fair share of the output budget, and clipped blocks name the spill file and line range. The final non-nil value is printed. text and image return nil.
JSON objects become keyword-keyed maps; arrays become vectors. Keys retain their exact spelling, including underscores and camelCase. Keyword arguments become JSON strings. Duplicate encoded keys and non-JSON values fail.
(image block) forwards an image and (exit) ends the script. all-settled yields {:status :fulfilled :value ...} or {:status :rejected :error {:message ...}}. Calls still running at script completion are cancelled.
(catalog/search query {:limit 8 :namespace "..."}) returns {:items [...] :truncated bool} of records with canonical :id, :name, :symbol, description, and schemas.
defsession values must be literal JSON-compatible data. Persistent macros and computed initializers are unsupported.
JJ commit records use :change-id, :commit-id, :description, :author, :parents, and :empty?. Image reads have :kind "image" and an :image value to forward with image; omitted images have :kind "image-omitted" and :note. Guix builds add :outputs and :outputs_complete to command actions. Raw jj/run and guix/run are lower-level fixed-program fallbacks.
models/get-models-of-type, models/get-available-of-type, models/get-model-of-type, models/classify, and models/generate-images preserve Pi's model API arguments. Check :stopReason and :errorMessage. Forward image blocks with image, never text.
`;

export function sciDescription(
	loadout: ToolLoadout,
	entries: Capability[],
	budget: number,
	models: boolean,
) {
	const domains = DOMAINS.filter((domain) => entries.some((entry) => entry.id === domain.id));
	const first = HEAD_DOMAINS.flatMap((id) => domains.filter((domain) => domain.id === id));
	const visible = entries.filter(
		(entry) =>
			loadout.getExposure(entry.id) !== "deferred" &&
			!domains.some((domain) => domain.id === entry.id),
	);
	const plain = visible.filter((entry) => !entry.namespace?.name);
	const namespaced = visible.filter((entry) => !plain.includes(entry));
	const lines = [
		...first.flatMap(signatures),
		...plain.map(toolSignature),
		...domains
			.filter((domain) => !first.includes(domain))
			.sort((a, b) => order(a.id) - order(b.id))
			.flatMap(signatures),
		...namespaced.map(toolSignature),
	];
	// Fill the part that survives truncation in priority order, then move the
	// remainder after it.
	const header = lines.length ? `${SCI_BASICS}\n${SIGNATURE_HEADER}` : SCI_BASICS;
	let room = DESCRIPTION_HEAD - header.length - MORE_SIGNATURES.length - 2;
	const head: string[] = [];
	const later: string[] = [];
	for (const line of lines) {
		if (line.length + 1 <= room) {
			head.push(line);
			room -= line.length + 1;
		} else later.push(line);
	}
	let remaining = budget;
	const summaries: string[] = [];
	for (const entry of [...plain, ...namespaced]) {
		const text = summary(entry);
		const cost = Math.ceil(text.length / 4);
		if (cost > remaining) continue;
		remaining -= cost;
		summaries.push(text);
	}
	const namespaces = new Map<string, string>();
	for (const entry of namespaced)
		if (entry.namespace && !namespaces.has(entry.namespace.name))
			namespaces.set(
				entry.namespace.name,
				`Namespace ${entry.namespace.name}. Discover its tools with catalog/search.\n${entry.namespace.description ?? ""}`,
			);
	return [
		[header, ...head, lines.length ? MORE_SIGNATURES : ""].filter(Boolean).join("\n"),
		later.join("\n"),
		SCI_INTRO,
		!models ? "Model capabilities are disabled." : "",
		...namespaces.values(),
		summaries.length ? `Tool summaries:\n${summaries.join("\n")}` : "",
	]
		.filter(Boolean)
		.join("\n\n");
}

import { fileURLToPath } from "node:url";
import { toCodemodeIdentifier } from "@earendil-works/pi-codemode";
import type { ToolLoadout, ToolNamespace } from "@earendil-works/pi-coding-agent";

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
				description: [
					tool.name === "bash"
						? "Shell escape hatch. Prefer semantic jj, guix, repo, make, fs, and search SCI functions. Use Pi-backed search rather than an rg process. Keep ordinary data analysis in SCI; do not generate temporary interpreter programs."
						: "",
					tool.description,
					...bullets,
				]
					.filter(Boolean)
					.join("\n"),
				inputSchema: tool.parameters,
				outputSchema: tool.outputSchema,
				namespace: namespace(tool.name),
			} satisfies Capability;
		});
}

export function declaration(entry: Capability) {
	return (
		`### ${entry.symbol}\n${entry.description}\nCall (await (${entry.symbol} {...})).\nInput JSON Schema: ${JSON.stringify(entry.inputSchema)}\n` +
		(entry.outputSchema
			? `Result JSON Schema: ${JSON.stringify(entry.outputSchema)}`
			: "Result is a string.")
	);
}

export const SCI_INTRO = `Run restricted Clojure with SCI inside Pi's QuickJS sandbox.
The input is raw Clojure source, not JSON or a code fence. The body is async; use (await ...) for tool, discovery, and model calls. Helper functions containing await need ^:async metadata.
Example: (text (mapv #(* % 2) [1 2 3]))
Read ${fileURLToPath(new URL("../README.md", import.meta.url))} for the active SCI codemode documentation. Installed Pi docs/codemode.md describes the disabled JavaScript evaluator.
For JSON files, use (await (fs/read-json "package.json")) and inspect :value. Do not parse display-limited tools/read output. For session JSONL, use fs/read-jsonl with :fields projections and :next_cursor. Follow all pages before claiming complete coverage. :value_truncated marks clipped strings.
Reduce results before printing. Print schemas only when needed, not whole discovery results or transcripts. Store only small summaries or cursors, never raw transcripts. One stored value is limited to 262144 JSON characters; the whole store to 1048576.
Optional first line: ;; @options: {"max_output_tokens": 10000, "timeout_ms": 60000}
No JavaScript interop, arbitrary require, eval, filesystem, process, network, or timers. Effects go through Pi's injected capabilities. require forms must precede executable forms and can only select provided namespaces.
Strings print directly. Collections print as EDN. The final non-nil value is printed. text and image return nil.
JSON objects become keyword-keyed maps; arrays become vectors. Keys retain their exact spelling, including underscores and camelCase. Keyword arguments become JSON strings. Duplicate encoded keys and non-JSON values fail.
(text value), (image block), (exit), (store "key" value), (load "key" default), and (unstore "key") are available. Store changes persist only on success and follow the session branch. Tool effects are not rolled back.
(await (all [...])), (await (all-settled [...])) compose eager calls. all-settled yields {:status :fulfilled :value ...} or {:status :rejected :error {:message ...}}. Calls still running at script completion are cancelled.
(catalog/search query {:limit 8 :namespace "..."}), (catalog/describe id), (catalog/namespace name), and (catalog/invoke id args) return promises. Search and describe return canonical :id, :name, :symbol, description, and schemas. Discover and describe before invoking an unlisted tool.
Aliases tools, catalog, models, json, str, and set are prebound. json/parse and json/generate convert JSON. clojure.string and clojure.set are available. Ordinary def and defn are temporary. Explicit top-level (defsession name value) and (defsession ^:async name [args] ...) persist in the session namespace on success. Values must be literal JSON-compatible data; functions cannot capture invocation-local names. Persistent macros and computed initializers are unsupported. Use session/name, (session/definitions), (session/source 'name), and (session/forget 'name). Code and store changes commit together on the active branch. Conflicts do not retry external effects.
Run programs on the host; write programs in SCI. Semantic namespaces jj, guix, make, repo, fs, and search exist only when their registered tools are callable. Use (await (jj/log {:revisions "@" :limit 10})), (await (jj/files-changed)), (await (guix/build {:file "package.scm"})), (await (make/run "target")), (await (repo/pi-sci-test)), (await (search/text "pattern" {:paths ["src"]})), (await (search/files {:glob "*.clj"})), or (await (fs/read "file" {:start_line 20 :end_line 50})). Collection results have :items and :truncated. JJ commit records use :change-id, :commit-id, :description, :author, :parents, and :empty?. Text file reads have :text, :start_line, :end_line, :total_lines, :next_line, and :truncated. fs/exists? returns a boolean. Successful image reads have :kind "image" and an :image value to forward with image; omitted images have :kind "image-omitted" and :note. Command actions have :ok, :exit_code, :stderr, :timed_out, :truncated, and :duration_ms; Guix builds add :outputs and :outputs_complete. (result/check r) checks command actions; use structured fields for read operations, not stdout!. Use :cwd and :timeout_ms when needed; command actions also accept :env. There is no git or rg namespace, generic process/run, or shell/run. Search uses Pi's search implementation. Raw jj/run and guix/run are lower-level fixed-program fallbacks. Bash is an escape hatch. Keep parsing, filtering, grouping, sorting, aggregation, joins, and command selection in SCI. Do not generate temporary interpreter programs for ordinary analysis. Collect independent effects first, await them together, then reduce in SCI. Consider defsession after repeating a nontrivial pattern; do not persist one-off transforms.
models/get-models-of-type, models/get-available-of-type, models/get-model-of-type, models/classify, and models/generate-images preserve Pi's model API arguments. Check :stopReason and :errorMessage. Forward image blocks with image, never text.
`;

export function sciDescription(
	loadout: ToolLoadout,
	entries: Capability[],
	budget: number,
	models: boolean,
) {
	const visible = entries.filter((entry) => loadout.getExposure(entry.id) !== "deferred");
	let remaining = budget;
	const sections: string[] = [];
	const groups = new Map<string, Capability[]>();
	for (const entry of visible) {
		const name = entry.namespace?.name ?? "";
		const group = groups.get(name) ?? [];
		group.push(entry);
		groups.set(name, group);
	}
	const queues = [...groups].map(([namespace, tools]) => ({
		namespace,
		tools: tools
			.map((entry) => ({ entry, text: declaration(entry) }))
			.sort((a, b) => a.text.length - b.text.length),
	}));
	for (let active = queues; active.length > 0; ) {
		active = active.filter((group) => {
			const next = group.tools[0];
			const cost = Math.ceil(next.text.length / 4);
			if (cost > remaining) return false;
			remaining -= cost;
			sections.push(next.text);
			group.tools.shift();
			return group.tools.length > 0;
		});
	}
	const namespaces = [...groups.entries()]
		.filter(([name]) => Boolean(name))
		.map(
			([name, entries]) =>
				`Namespace ${name}. Discover its tools with catalog/search.\n${entries[0]?.namespace?.description ?? ""}`,
		);
	return [SCI_INTRO, !models ? "Model capabilities are disabled." : "", ...namespaces, ...sections]
		.filter(Boolean)
		.join("\n\n");
}

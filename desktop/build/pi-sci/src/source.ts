import { readFileSync } from "node:fs";
import { parseCodemodeSource } from "@earendil-works/pi-codemode/source";
import { DOMAIN_COMMANDS } from "./commands.ts";
import { ENVIRONMENT_KEY, ENVIRONMENT_RUNTIME, type Environment } from "./environment.ts";

export const SCI_GRAMMAR = String.raw`
start: options_source | plain_source
options_source: OPTIONS_LINE NEWLINE SOURCE
plain_source: SOURCE
OPTIONS_LINE: /[ \t]*;; @options:[^\r\n]*/
NEWLINE: /\r?\n/
SOURCE: /[\s\S]+/
`;

export function parseSciSource(source: string) {
	if (!source.trim()) throw new Error("Expected non-empty Clojure source.");
	const normalized = source.replace(/^([ \t]*);; @options:/, "$1// @options:");
	return parseCodemodeSource(normalized);
}

export function compileSciSource(
	source: string,
	catalog: unknown[] = [],
	environment: Environment = { revision: "", operations: [] },
) {
	const parsed = parseSciSource(source);
	let bundle: string;
	try {
		bundle = readFileSync(new URL("../dist/sci.js", import.meta.url), "utf8");
	} catch (error) {
		throw new Error("SCI bundle could not be loaded. Run make pi-sci, then reload Pi.", {
			cause: error,
		});
	}
	const options = parsed.options;
	const directive =
		options.maxOutputTokens !== undefined || options.timeoutMs !== undefined
			? "// @options: " +
				JSON.stringify({
					...(options.maxOutputTokens !== undefined
						? { max_output_tokens: options.maxOutputTokens }
						: {}),
					...(options.timeoutMs !== undefined ? { timeout_ms: options.timeoutMs } : {}),
				}) +
				"\n"
			: "";
	return (
		directive +
		"let piSciRun;\n" +
		bundle +
		`
const entries = ${JSON.stringify(catalog)};
const byId = new Map(entries.map(entry => [entry.id, entry]));
const byName = new Map(entries.map(entry => [entry.name, entry]));
const invoke = (id, args) => {
	const entry = byId.get(id) || byName.get(id);
	if (!entry) throw new Error("Tool is not callable: " + id);
	return tools[entry.name](args);
};
const catalog = {
	search: async (query, options) => {
		const matches = await searchTools(query, options);
		return matches.map(match => byName.get(match.name)).filter(Boolean);
	},
	describe: id => byId.get(id) || byName.get(id),
	namespace: (...args) => describeNamespace(...args),
	invoke,
};
const publicKey = key => {
	if (typeof key !== "string" || key.startsWith("__pi_sci/")) throw new Error("Invalid or reserved store key.");
	return key;
};
await piSciRun(${JSON.stringify(parsed.code)}, {
	commands: ${JSON.stringify(DOMAIN_COMMANDS)},
	environment: ${JSON.stringify(environment.operations)},
	commitEnvironment: operations => store(${JSON.stringify(ENVIRONMENT_KEY)}, {
		version: 1, runtime: ${JSON.stringify(ENVIRONMENT_RUNTIME)}, reset: false, operations,
	}),
	tools, catalog, models: typeof models === "undefined" ? {} : {
		"get-models-of-type": models.getModelsOfType,
		"get-available-of-type": models.getAvailableOfType,
		"get-model-of-type": models.getModelOfType,
		classify: models.classify,
		"generate-images": models.generateImages,
	},
	text, image, exit,
	store: (key, value) => store(publicKey(key), value),
	load: key => load(publicKey(key)),
	unstore: key => store(publicKey(key), undefined),
});
`
	);
}

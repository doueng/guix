import {
	type CodemodeStoreEntryData,
	createCodemodeExtension,
	type ExtensionAPI,
	highlightCode,
	type ToolDefinition,
} from "@earendil-works/pi-coding-agent";
import { Container, Text } from "@earendil-works/pi-tui";
import type { TObject, TString } from "typebox";
import { capabilities, SCI_INTRO, sciDescription } from "./catalog.ts";
import {
	assertEnvironmentLimits,
	ENVIRONMENT_KEY,
	ENVIRONMENT_RUNTIME,
	environmentSummary,
	parseEnvironmentDelta,
	readEnvironment,
} from "./environment.ts";
import { compileSciSource, SCI_GRAMMAR } from "./source.ts";

export default function sciCodemode(pi: ExtensionAPI) {
	const adapter: ExtensionAPI = {
		...pi,
		registerTool(registered) {
			if (registered.name !== "codemode") throw new Error("Expected Pi's codemode definition.");
			const original = registered as unknown as ToolDefinition<TObject<{ code: TString }>>;
			Object.assign(original.parameters.properties.code, {
				description: "Raw Clojure source.",
			});
			pi.registerTool({
				...original,
				description: SCI_INTRO,
				promptSnippet: "Run Clojure that calls other tools",
				promptGuidelines: [
					"Use SCI codemode to batch independent calls, compose results, and filter output. Await every tool and model call.",
					"Persist reusable helpers with top-level (defsession ^:async name [args] ...), or literal constants with (defsession name value). Use session/name on later calls. Ordinary def is temporary. Inspect with (session/definitions), (session/source 'name), and (session/forget 'name).",
				],
				constrainedSampling: { type: "grammar", variants: { openai_lark: SCI_GRAMMAR } },
				prepareLoadout(loadout) {
					const changes = original.prepareLoadout?.(loadout);
					const metadata = new Map(pi.getAllTools().map((tool) => [tool.name, tool]));
					const entries = capabilities(
						loadout.callable,
						(name) => loadout.getNamespace(name),
						(name) => metadata.get(name)?.promptGuidelines ?? [],
					);
					const budget = pi.getSettings().codemode?.inlineBudget ?? 3000;
					const descriptions: Record<string, string> = {
						...changes?.descriptions,
						codemode: sciDescription(loadout, entries, budget, true),
					};
					for (const tool of loadout.declared) {
						const entry = entries.find((entry) => entry.id === tool.name);
						if (entry)
							descriptions[tool.name] =
								`${entry.description}\nCodemode call: (await (${entry.symbol} {...})).`;
					}
					return { ...changes, descriptions };
				},
				async execute(id, params, signal, update, ctx) {
					const startingBranch = ctx.sessionManager.getBranch();
					const anchor = ctx.sessionManager.getLeafId();
					const lastCommit = (branch: typeof startingBranch) =>
						branch
							.filter((entry) => entry.type === "custom" && entry.customType === "codemode-store")
							.at(-1)?.id;
					const startingCommit = lastCommit(startingBranch);
					const sessionId = ctx.sessionManager.getSessionId();
					const environment = readEnvironment(startingBranch);
					let pending: CodemodeStoreEntryData | undefined;
					let executor: typeof original | undefined;
					createCodemodeExtension()({
						...pi,
						registerTool(tool) {
							executor = tool as unknown as typeof original;
						},
						appendEntry(type, data) {
							if (type !== "codemode-store" || pending)
								throw new Error("Unexpected SCI transaction write.");
							pending = data as CodemodeStoreEntryData;
						},
					});
					if (!executor) throw new Error("Pi did not provide a codemode executor.");
					const metadata = new Map(pi.getAllTools().map((tool) => [tool.name, tool]));
					const entries = capabilities(
						ctx.tools,
						(name) => metadata.get(name)?.namespace,
						(name) => metadata.get(name)?.promptGuidelines ?? [],
					);
					const code = compileSciSource(params.code, entries, environment);
					const result = await executor.execute(id, { ...params, code }, signal, update, ctx);
					if (result.isError) return result;
					try {
						if (signal?.aborted) throw new Error("SCI invocation was aborted before commit.");
						const branch = ctx.sessionManager.getBranch();
						if (
							ctx.sessionManager.getSessionId() !== sessionId ||
							(anchor !== null && !branch.some((entry) => entry.id === anchor)) ||
							lastCommit(branch) !== startingCommit ||
							readEnvironment(branch).revision !== environment.revision
						) {
							throw new Error(
								"SCI branch or environment changed during execution. Nothing was committed. Do not automatically retry external effects.",
							);
						}
						if (pending) {
							const { [ENVIRONMENT_KEY]: rawDelta, ...set } = pending.set;
							if (
								Object.keys(set).some((key) => key.startsWith("__pi_sci/")) ||
								pending.delete.some((key) => key.startsWith("__pi_sci/"))
							)
								throw new Error("Reserved SCI transaction key.");
							const delta = rawDelta === undefined ? undefined : parseEnvironmentDelta(rawDelta);
							if (delta?.reset)
								throw new Error("Only the explicit recovery command may reset the SCI library.");
							if (delta) assertEnvironmentLimits([...environment.operations, ...delta.operations]);
							pi.appendEntry("codemode-store", {
								set,
								delete: pending.delete,
								...(delta ? { sciEnvironment: delta } : {}),
							});
						}
						return result;
					} catch (error) {
						const notice = {
							type: "text" as const,
							text: `SCI transaction failed: ${error instanceof Error ? error.message : String(error)} External tool effects were not rolled back.`,
						};
						const [header, ...output] = result.content;
						return {
							...result,
							isError: true,
							content:
								header?.type === "text" && header.text.startsWith("Script completed\n")
									? [
											{
												...header,
												text: header.text.replace(/^Script completed/, "Script failed"),
											},
											notice,
											...output,
										]
									: [notice, ...result.content],
						};
					}
				},
				renderCall(args, theme, context) {
					const view = new Container();
					view.addChild(new Text(theme.fg("toolTitle", theme.bold("codemode · SCI")), 0, 0));
					const code = typeof args.code === "string" ? args.code : "[invalid source]";
					const lines = code.replace(/\r/g, "").trimEnd().split("\n");
					const preview = context.expanded ? lines.join("\n") : lines.slice(0, 8).join("\n");
					view.addChild(new Text(highlightCode(preview, "clojure").join("\n"), 0, 0));
					if (!context.expanded && lines.length > 8)
						view.addChild(new Text("... expand to see the complete program.", 0, 0));
					return view;
				},
			});
		},
	};
	pi.on("before_agent_start", (event, ctx) => {
		let summary: string;
		try {
			summary = environmentSummary(readEnvironment(ctx.sessionManager.getBranch()));
		} catch (error) {
			summary = `SCI library unavailable: ${error instanceof Error ? error.message : String(error)}`;
		}
		return { systemPrompt: `${event.systemPrompt}\n\n${summary}` };
	});
	pi.registerCommand("sci-library", {
		description:
			"Inspect the branch SCI library, or explicitly reset its declarations without changing stored task data",
		handler: async (args, ctx) => {
			if (args.trim() === "reset") {
				pi.appendEntry("codemode-store", {
					set: {},
					delete: [],
					sciEnvironment: {
						version: 1,
						runtime: ENVIRONMENT_RUNTIME,
						reset: true,
						operations: [],
					},
				});
				ctx.ui.notify("SCI library reset on this branch. Stored task data is unchanged.", "info");
				return;
			}
			if (args.trim()) throw new Error("Use /sci-library or /sci-library reset.");
			ctx.ui.notify(environmentSummary(readEnvironment(ctx.sessionManager.getBranch())), "info");
		},
	});
	return createCodemodeExtension()(adapter);
}

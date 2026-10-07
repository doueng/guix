import {
	type AssistantMessage,
	createAssistantMessageEventStream,
	getCurrentTools,
} from "@earendil-works/pi-ai";
import {
	createCodemodeExtension,
	type ExtensionAPI,
	type ExtensionFactory,
} from "@earendil-works/pi-coding-agent";
import { SCI_GRAMMAR } from "../src/source.ts";

export const PNG =
	"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l1sAAAAASUVORK5CYII=";
export const USAGE = {
	input: 10,
	output: 2,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 12,
	cost: { input: 0.01, output: 0.02, cacheRead: 0, cacheWrite: 0, total: 0.03 },
};

export default fixtureProvider(
	(() => {
		const sources: string[] = JSON.parse(process.env.PI_SCI_TEST_SCRIPTS ?? "[]");
		return () => sources.shift();
	})(),
);

export function fixtureProvider(nextSource: () => string | undefined): ExtensionFactory {
	let call = 0;
	return (pi) => {
		let schema: unknown;
		createCodemodeExtension()({
			...pi,
			registerTool(definition) {
				schema = definition.parameters;
			},
		} satisfies ExtensionAPI);
		pi.registerProvider("sci-fixture", {
			baseUrl: "http://127.0.0.1/unused",
			apiKey: "fixture",
			api: "sci-fixture-chat",
			models: [
				{
					id: "chat",
					name: "Fixture",
					reasoning: false,
					input: ["text", "image"],
					contextWindow: 100000,
					maxTokens: 1000,
					cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				},
				{
					type: "classifier",
					id: "judge",
					name: "Fixture judge",
					api: "sci-fixture-classifier",
					input: ["text"],
					contextWindow: 100000,
					cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				},
				{
					type: "image",
					id: "painter",
					name: "Fixture painter",
					api: "sci-fixture-image",
					input: ["text"],
					output: ["image"],
					cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				},
			],
			streamSimple(model, context) {
				const declared = getCurrentTools(context.messages).find((tool) => tool.name === "codemode");
				const sampling = declared?.constrainedSampling;
				if (
					!declared?.description.startsWith("Run restricted Clojure") ||
					!sampling ||
					sampling.type !== "grammar" ||
					sampling.variants.openai_lark !== SCI_GRAMMAR
				) {
					throw new Error("Codemode must advertise only Clojure and the SCI grammar.");
				}
				if (pi.getAllTools().find((tool) => tool.name === "codemode")?.parameters !== schema) {
					throw new Error("The replacement must preserve Pi's codemode schema identity.");
				}
				const stream = createAssistantMessageEventStream();
				const code = nextSource();
				const message: AssistantMessage = {
					role: "assistant",
					content: [],
					api: model.api,
					provider: model.provider,
					model: model.id,
					usage: {
						input: 0,
						output: 0,
						cacheRead: 0,
						cacheWrite: 0,
						totalTokens: 0,
						cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
					},
					stopReason: "pending",
					timestamp: Date.now(),
				};
				queueMicrotask(() => {
					stream.push({ type: "start", partial: message });
					if (code !== undefined) {
						const toolCall = {
							type: "toolCall" as const,
							id: `fixture/${++call}`,
							name: "codemode",
							arguments: { code },
						};
						message.content.push(toolCall);
						stream.push({ type: "toolcall_start", contentIndex: 0, partial: message });
						stream.push({ type: "toolcall_end", contentIndex: 0, toolCall, partial: message });
						message.stopReason = "toolUse";
						stream.push({ type: "done", reason: "toolUse", message });
					} else {
						message.content.push({ type: "text", text: "fixture complete" });
						message.stopReason = "stop";
						stream.push({ type: "done", reason: "stop", message });
					}
					stream.end();
				});
				return stream;
			},
			classifiers: {
				"sci-fixture-classifier": {
					classify: async (model, context) => ({
						api: model.api,
						timestamp: Date.now(),
						provider: model.provider,
						model: model.id,
						usage: USAGE,
						stopReason: "stop",
						answers: Object.fromEntries(
							Object.keys(context.questions).map((id) => [id, { type: "bool", probability: 0.75 }]),
						),
					}),
				},
			},
			images: {
				"sci-fixture-image": {
					generateImages: async (model) => ({
						api: model.api,
						timestamp: Date.now(),
						provider: model.provider,
						model: model.id,
						usage: USAGE,
						stopReason: "stop",
						output: [{ type: "image", data: PNG, mimeType: "image/png" }],
					}),
				},
			},
		});
	};
}

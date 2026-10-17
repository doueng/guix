import { readFileSync } from "node:fs";
import {
	type AssistantMessage,
	createAssistantMessageEventStream,
	type JsonObject,
} from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

type Step = string | { name: string; arguments: JsonObject };

export default function (pi: ExtensionAPI) {
	const path = process.env.BBH_EVAL_SCRIPT;
	if (!path) throw new Error("BBH_EVAL_SCRIPT is required");
	const steps: Step[] = JSON.parse(readFileSync(path, "utf8"));
	let index = 0;
	pi.registerProvider("bbh-eval", {
		apiKey: "fixture",
		baseUrl: "http://127.0.0.1/unused",
		api: "bbh-eval",
		models: [
			{
				id: "script",
				name: "Scripted evaluation",
				input: ["text"],
				reasoning: false,
				contextWindow: 128000,
				maxTokens: 4096,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			},
		],
		streamSimple(model, _context, options) {
			const stream = createAssistantMessageEventStream();
			const step = steps[index++];
			if (step === undefined) throw new Error("Evaluation script exhausted");
			const message: AssistantMessage = {
				role: "assistant",
				api: model.api,
				provider: model.provider,
				model: model.id,
				timestamp: Date.now(),
				content: [],
				stopReason: "pending",
				usage: {
					input: 0,
					output: 0,
					cacheRead: 0,
					cacheWrite: 0,
					totalTokens: 0,
					cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
				},
			};
			queueMicrotask(() => {
				stream.push({ type: "start", partial: message });
				if (options?.signal?.aborted) {
					message.stopReason = "aborted";
					message.errorMessage = "Aborted";
					stream.push({ type: "error", reason: "aborted", error: message });
				} else if (typeof step === "string") {
					const block = { type: "text" as const, text: "" };
					message.content.push(block);
					stream.push({ type: "text_start", contentIndex: 0, partial: message });
					for (const delta of step.match(/.{1,3}|\n/gu) ?? []) {
						block.text += delta;
						stream.push({ type: "text_delta", contentIndex: 0, delta, partial: message });
					}
					stream.push({ type: "text_end", contentIndex: 0, content: step, partial: message });
					message.stopReason = "stop";
					stream.push({ type: "done", reason: "stop", message });
				} else {
					const toolCall = {
						type: "toolCall" as const,
						id: `eval-${index}`,
						name: step.name,
						arguments: step.arguments,
					};
					message.content.push(toolCall);
					stream.push({ type: "toolcall_start", contentIndex: 0, partial: message });
					stream.push({ type: "toolcall_end", contentIndex: 0, toolCall, partial: message });
					message.stopReason = "toolUse";
					stream.push({ type: "done", reason: "toolUse", message });
				}
				stream.end();
			});
			return stream;
		},
	});
}

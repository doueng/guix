import { readFileSync } from "node:fs";

export function readEvents(path) {
	return readFileSync(path, "utf8")
		.split("\n")
		.filter(Boolean)
		.flatMap((line) => {
			try {
				return [JSON.parse(line)];
			} catch {
				return [];
			}
		});
}

const text = (result) =>
	(result?.content ?? []).reduce((n, block) => n + (block.text?.length ?? 0), 0);

// Outer calls are the model's round trips; inner calls are tools a codemode
// script ran. promptTokens is the first request's context, which is the fixed
// cost of the harness (system prompt and tool declarations).
export function summarize(events) {
	const m = {
		turns: 0,
		outerCalls: 0,
		outerErrors: 0,
		innerCalls: 0,
		innerErrors: 0,
		firstValidCall: null,
		promptTokens: null,
		resultChars: 0,
		tools: {},
		usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0 },
		cost: 0,
		stopReason: null,
		errorMessage: null,
	};
	for (const event of events) {
		if (event.type === "message_end" && event.message?.role === "assistant") {
			const usage = event.message.usage ?? {};
			m.turns++;
			m.promptTokens ??= (usage.input ?? 0) + (usage.cacheRead ?? 0) + (usage.cacheWrite ?? 0);
			for (const key of Object.keys(m.usage)) m.usage[key] += usage[key] ?? 0;
			m.cost += usage.cost?.total ?? 0;
			m.stopReason = event.message.stopReason ?? null;
			m.errorMessage = event.message.errorMessage ?? null;
		}
		if (event.type !== "tool_execution_end") continue;
		if (event.parentToolCallId) {
			m.innerCalls++;
			if (event.isError) m.innerErrors++;
			continue;
		}
		m.outerCalls++;
		m.resultChars += text(event.result);
		m.tools[event.toolName] = (m.tools[event.toolName] ?? 0) + 1;
		if (event.isError) m.outerErrors++;
		else m.firstValidCall ??= m.outerCalls;
	}
	return m;
}

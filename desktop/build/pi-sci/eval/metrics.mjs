import { closeSync, openSync, readFileSync, readSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const UPDATE_PREFIX = Buffer.from('{"type":"tool_execution_update"');

// Streams the file, because progress updates can make it larger than a Node
// string. Updates are skipped: no metric uses them, and they hold most bytes.
export function readEvents(path) {
	const events = [];
	const fd = openSync(path, "r");
	const chunk = Buffer.alloc(1 << 20);
	let pending = [];
	let skipping = false;
	const finish = (parts) => {
		const line = Buffer.concat(parts);
		if (line.length === 0 || line.subarray(0, UPDATE_PREFIX.length).equals(UPDATE_PREFIX)) return;
		try {
			events.push(JSON.parse(line.toString("utf8")));
		} catch {}
	};
	try {
		for (;;) {
			const n = readSync(fd, chunk, 0, chunk.length, null);
			if (n <= 0) break;
			let start = 0;
			for (
				let end = chunk.indexOf(10, start);
				end !== -1 && end < n;
				end = chunk.indexOf(10, start)
			) {
				if (!skipping) finish([...pending, chunk.subarray(start, end)]);
				pending = [];
				skipping = false;
				start = end + 1;
			}
			if (start < n && !skipping) {
				pending.push(Buffer.from(chunk.subarray(start, n)));
				const head = Buffer.concat(pending.slice(0, 1)).subarray(0, UPDATE_PREFIX.length);
				if (head.length === UPDATE_PREFIX.length && head.equals(UPDATE_PREFIX)) {
					skipping = true;
					pending = [];
				}
			}
		}
		if (!skipping && pending.length) finish(pending);
	} finally {
		closeSync(fd);
	}
	return events;
}

// Each pi invocation writes one session header, so a session with several
// prompts splits there into one segment per prompt. agent_start would also
// split at Pi's automatic retries.
export function promptSegments(events) {
	const segments = [];
	let current = [];
	for (const event of events) {
		if (event.type === "session" && current.some((e) => e.type === "session")) {
			segments.push(current);
			current = [];
		}
		current.push(event);
	}
	if (current.length) segments.push(current);
	return segments;
}

const PROVIDER_PRICES = { "claude-bridge": "anthropic" };

// List price in dollars, from Pi's model store. Subscription providers report
// $0, so this is the comparable cost across runs.
export function priceFor(model) {
	const [provider, ...rest] = model.split("/");
	const id = rest.join("/");
	const store = JSON.parse(readFileSync(join(homedir(), ".pi/agent/models-store.json"), "utf8"));
	const models = store[PROVIDER_PRICES[provider] ?? provider]?.models ?? [];
	return models.find((entry) => entry.id === id)?.cost ?? null;
}

export function listCost(usage, price) {
	if (!price) return NaN;
	return (
		(usage.input * price.input +
			usage.output * price.output +
			usage.cacheRead * price.cacheRead +
			usage.cacheWrite * price.cacheWrite) /
		1e6
	);
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

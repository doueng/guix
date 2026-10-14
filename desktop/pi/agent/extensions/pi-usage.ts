import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const FIREWORKS_ORIGIN = "https://api.fireworks.ai";
const CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
const ANTHROPIC_USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
const CLAUDE_CREDENTIALS_PATH = join(homedir(), ".claude", ".credentials.json");
const FIREWORKS_WINDOW_DAYS = 30;
const TTL_MS = 5 * 60 * 1000;
const STATUS_ID = "usage";
const REQUEST_TIMEOUT_MS = 20_000;

type UsageCtx = {
	ui?: {
		setStatus(id: string, text?: string): void;
		notify(message: string, level?: "info" | "warning" | "error"): void;
	};
	modelRegistry?: { getProviderAuth(id: string): unknown };
	model?: { provider?: string };
};

type Money = { currencyCode?: string; units?: string; nanos?: number };

type FireworksSpend = {
	total: number;
	series: Array<{ label: string; amount: number }>;
	accounts: string[];
};

type RateWindow = { used_percent?: number; limit_window_seconds?: number; reset_at?: number };

type RateLimits = {
	windows: Array<{ label: string; used: number; resetsAt?: number }>;
	plan?: string;
};

type Entry<T> = {
	title: string;
	providers: string[];
	authorize: (ctx: UsageCtx) => Promise<string | undefined>;
	query: (authorization: string | undefined) => Promise<T>;
	render: (data: T) => string;
	describe: (data: T) => string[];
};

type AnyEntry = {
	id: string;
	title: string;
	providers: string[];
	authorize: (ctx: UsageCtx) => Promise<string | undefined>;
	query: (authorization: string | undefined) => Promise<unknown>;
	render: (data: unknown) => string;
	describe: (data: unknown) => string[];
};

type CacheSlot = { data?: unknown; error?: string; at: number };

const FIREWORKS_SERIES_LABELS: Record<string, string> = {
	SERVERLESS: "Serverless",
	DEDICATED_DEPLOYMENT: "Dedicated deployments",
	TRAINING: "Training",
};

function dayFloor(time: number): string {
	return `${new Date(time).toISOString().slice(0, 10)}T00:00:00Z`;
}

function formatUsd(amount: number): string {
	return `$${amount.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

async function bearerFromAuth(ctx: UsageCtx, providerId: string): Promise<string | undefined> {
	try {
		const result = (await ctx.modelRegistry?.getProviderAuth(providerId)) as
			| { auth?: { apiKey?: string; headers?: Record<string, string | null> } }
			| undefined;
		const headerAuth = Object.entries(result?.auth?.headers ?? {}).find(
			([key]) => key.toLowerCase() === "authorization",
		)?.[1];
		return headerAuth ?? (result?.auth?.apiKey ? `Bearer ${result.auth.apiKey}` : undefined);
	} catch {
		return undefined;
	}
}

async function fetchJson(
	url: string,
	authorization: string | undefined,
	description: string,
	headers: Record<string, string> = {},
): Promise<Record<string, unknown>> {
	if (!authorization) throw new Error("no credentials");
	const response = await fetch(url, {
		headers: { ...headers, Authorization: authorization },
		signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
	});
	if (!response.ok) throw new Error(`${description} ${response.status}`);
	return (await response.json()) as Record<string, unknown>;
}

function windowLabel(seconds: number | undefined): string {
	if (!seconds || seconds <= 0) return "window";
	if (seconds < 3600) return `${Math.ceil(seconds / 60)}m`;
	if (seconds < 86_400) return `${Math.round(seconds / 3600)}h`;
	return `${Math.round(seconds / 86_400)}d`;
}

function resetLabel(resetsAt: number | undefined): string {
	if (!resetsAt) return "";
	const minutes = Math.max(0, Math.round((resetsAt * 1000 - Date.now()) / 60_000));
	if (minutes < 60) return `, resets in ${minutes}m`;
	const hours = Math.round(minutes / 60);
	if (hours < 48) return `, resets in ${hours}h`;
	return `, resets in ${Math.round(hours / 24)}d`;
}

const PROVIDER_FIREWORKS = "fireworks";
const PROVIDER_CODEX = "openai-codex";
const PROVIDER_ANTHROPIC = "anthropic";

async function queryFireworks(authorization: string | undefined): Promise<FireworksSpend> {
	const accountsPayload = await fetchJson(
		`${FIREWORKS_ORIGIN}/v1/accounts?pageSize=200`,
		authorization,
		"Fireworks accounts",
	);
	const accountsRaw = Array.isArray(accountsPayload.accounts) ? accountsPayload.accounts : [];
	const accounts = accountsRaw
		.map((account) => /\/([^/]+)$/.exec(String((account as { name?: string }).name ?? ""))?.[1])
		.filter((id): id is string => Boolean(id));
	if (accounts.length === 0) throw new Error("no Fireworks accounts");

	const now = Date.now();
	const start = dayFloor(now - (FIREWORKS_WINDOW_DAYS - 1) * 24 * 60 * 60 * 1000);
	const end = dayFloor(now + 24 * 60 * 60 * 1000);

	const bySeries = new Map<string, number>();
	let total = 0;
	for (const accountId of accounts) {
		const payload = await fetchJson(
			`${FIREWORKS_ORIGIN}/v1/accounts/${accountId}/billing/summary?startTime=${start}&endTime=${end}`,
			authorization,
			"Fireworks billing",
		);
		for (const raw of Array.isArray(payload.lineItems) ? payload.lineItems : []) {
			const item = raw as { series?: string; totalCost?: Money };
			const cost = item.totalCost;
			if (!cost?.currencyCode) continue;
			const amount = (cost.units === undefined ? 0 : Number(cost.units)) + (cost.nanos ?? 0) / 1e9;
			total += amount;
			bySeries.set(item.series ?? "other", (bySeries.get(item.series ?? "other") ?? 0) + amount);
		}
	}
	const series = [...bySeries.entries()]
		.map(([key, amount]) => ({ label: FIREWORKS_SERIES_LABELS[key] ?? "Other", amount }))
		.filter((entry) => entry.amount !== 0)
		.sort((a, b) => b.amount - a.amount);
	return { total, series, accounts };
}

const fireworksEntry: Entry<FireworksSpend> = {
	title: `Fireworks, last ${FIREWORKS_WINDOW_DAYS} days`,
	providers: [PROVIDER_FIREWORKS],
	authorize: (ctx) => bearerFromAuth(ctx, PROVIDER_FIREWORKS),
	query: queryFireworks,
	render: (spend) => `fw ${formatUsd(spend.total)}`,
	describe: (spend) => [
		...spend.series.map((entry) => `  ${entry.label}: ${formatUsd(entry.amount)}`),
		...(spend.series.length === 0 ? ["  (no rated line items)"] : []),
		`  Accounts: ${spend.accounts.join(", ")}`,
	],
};

async function queryCodex(authorization: string | undefined): Promise<RateLimits> {
	const payload = await fetchJson(CODEX_USAGE_URL, authorization, "Codex usage");
	const rateLimit = (payload.rate_limit ?? {}) as Record<string, RateWindow | null>;
	const windows: RateLimits["windows"] = [];
	for (const raw of Object.values(rateLimit)) {
		if (!raw || typeof raw.used_percent !== "number") continue;
		windows.push({
			label: windowLabel(raw.limit_window_seconds),
			used: raw.used_percent,
			resetsAt: typeof raw.reset_at === "number" ? raw.reset_at : undefined,
		});
	}
	return {
		windows,
		plan: typeof payload.plan_type === "string" ? payload.plan_type : undefined,
	};
}

function renderLimits(name: string, usage: RateLimits): string {
	return `${name} ${usage.windows.map((w) => `${w.label} ${Math.round(w.used)}%`).join(" · ")}`;
}

function describeLimits(usage: RateLimits): string[] {
	return [
		`  Plan: ${usage.plan ?? "unknown"}`,
		...usage.windows.map((w) => `  ${w.label} window: ${Math.round(w.used)}% used${resetLabel(w.resetsAt)}`),
	];
}

const codexEntry: Entry<RateLimits> = {
	title: "OpenAI Codex",
	providers: [PROVIDER_CODEX],
	authorize: (ctx) => bearerFromAuth(ctx, PROVIDER_CODEX),
	query: queryCodex,
	render: (usage) => renderLimits("codex", usage),
	describe: describeLimits,
};

const ANTHROPIC_WINDOWS: Record<string, string> = {
	five_hour: "5h",
	seven_day: "7d",
	seven_day_opus: "7d opus",
	seven_day_sonnet: "7d sonnet",
};

type ClaudeCredentials = {
	claudeAiOauth?: { accessToken?: string; expiresAt?: number; subscriptionType?: string };
};

async function readClaudeCredentials(): Promise<ClaudeCredentials["claudeAiOauth"]> {
	try {
		return (JSON.parse(await readFile(CLAUDE_CREDENTIALS_PATH, "utf8")) as ClaudeCredentials).claudeAiOauth;
	} catch {
		return undefined;
	}
}

// claude-bridge runs Claude Code, which keeps its own subscription token fresh; pi's anthropic login is the fallback.
async function authorizeAnthropic(ctx: UsageCtx): Promise<string | undefined> {
	const oauth = await readClaudeCredentials();
	if (oauth?.accessToken && (oauth.expiresAt ?? 0) > Date.now()) return `Bearer ${oauth.accessToken}`;
	return bearerFromAuth(ctx, PROVIDER_ANTHROPIC);
}

async function queryAnthropic(authorization: string | undefined): Promise<RateLimits> {
	const payload = await fetchJson(ANTHROPIC_USAGE_URL, authorization, "Anthropic usage", {
		"anthropic-beta": "oauth-2025-04-20",
	});
	const windows: RateLimits["windows"] = [];
	for (const [key, label] of Object.entries(ANTHROPIC_WINDOWS)) {
		const raw = payload[key] as { utilization?: number; resets_at?: string } | null | undefined;
		if (!raw || typeof raw.utilization !== "number") continue;
		const resetsAt = raw.resets_at ? Date.parse(raw.resets_at) / 1000 : Number.NaN;
		windows.push({ label, used: raw.utilization, resetsAt: Number.isFinite(resetsAt) ? resetsAt : undefined });
	}
	return { windows, plan: (await readClaudeCredentials())?.subscriptionType };
}

const anthropicEntry: Entry<RateLimits> = {
	title: "Anthropic",
	providers: [PROVIDER_ANTHROPIC, "claude-bridge"],
	authorize: authorizeAnthropic,
	query: queryAnthropic,
	render: (usage) => renderLimits("claude", usage),
	describe: describeLimits,
};

function asEntry<T>(id: string, entry: Entry<T>): AnyEntry {
	return {
		id,
		title: entry.title,
		providers: entry.providers,
		authorize: entry.authorize,
		query: (authorization) => entry.query(authorization),
		render: (data) => entry.render(data as T),
		describe: (data) => entry.describe(data as T),
	};
}

export default function piUsageExtension(pi: ExtensionAPI) {
	const entries: AnyEntry[] = [
		asEntry(PROVIDER_FIREWORKS, fireworksEntry),
		asEntry(PROVIDER_CODEX, codexEntry),
		asEntry(PROVIDER_ANTHROPIC, anthropicEntry),
	];
	const cache = new Map<string, CacheSlot>();
	const inFlight = new Map<string, Promise<unknown>>();

	const publishStatus = (ctx: UsageCtx) => {
		const current = entries.find(({ providers }) => providers.includes(ctx.model?.provider ?? ""));
		const visible = current ? [current] : entries;
		const parts: string[] = [];
		for (const { id, render } of visible) {
			const slot = cache.get(id);
			if (slot?.data !== undefined) parts.push(render(slot.data));
			else if (slot?.error) parts.push(`${id}: ${slot.error.slice(0, 40)}`);
		}
		ctx.ui?.setStatus(STATUS_ID, parts.length > 0 ? parts.join(" · ") : undefined);
	};

	const refreshOne = async (
		{ id, query }: AnyEntry,
		authorization: string,
		force: boolean,
	): Promise<void> => {
		const slot = cache.get(id);
		if (!force && slot && Date.now() - slot.at < TTL_MS) return;
		if (inFlight.has(id)) return inFlight.get(id)?.then(() => undefined);
		const load = query(authorization)
			.then((data) => {
				cache.set(id, { data, at: Date.now() });
			})
			.catch((error: unknown) => {
				cache.set(id, { error: error instanceof Error ? error.message : String(error), at: Date.now() });
			})
			.finally(() => {
				inFlight.delete(id);
			});
		inFlight.set(id, load);
		await load;
	};

	const refreshAll = async (ctx: UsageCtx, force: boolean): Promise<void> => {
		const active: Array<[AnyEntry, string]> = [];
		for (const entry of entries) {
			const authorization = await entry.authorize(ctx);
			if (authorization !== undefined) active.push([entry, authorization]);
		}
		await Promise.all(active.map(([entry, authorization]) => refreshOne(entry, authorization, force)));
		publishStatus(ctx);
	};

	pi.on("session_start", async (_event, ctx) => {
		ctx.ui.setStatus(STATUS_ID, "usage checking…");
		await refreshAll(ctx, false);
	});

	pi.on("agent_settled", async (_event, ctx) => {
		await refreshAll(ctx, false);
	});

	pi.on("model_select", async (_event, ctx) => {
		await refreshAll(ctx, false);
	});

	pi.registerCommand("usage", {
		description: "Show provider usage (Fireworks spend, Codex and Anthropic limits)",
		handler: async (_args, ctx) => {
			ctx.ui.setStatus(STATUS_ID, "usage checking…");
			await refreshAll(ctx, true);
			const sections: string[] = [];
			for (const { id, title, describe } of entries) {
				const slot = cache.get(id);
				if (slot?.data !== undefined) {
					sections.push(`${title}:`, ...describe(slot.data));
				} else if (slot?.error) {
					sections.push(`${id}: ${slot.error}`);
				}
			}
			if (sections.length === 0) {
				sections.push("No usage providers configured (needs Fireworks, Codex, or Anthropic credentials).");
			} else if (cache.get(PROVIDER_FIREWORKS)?.data !== undefined) {
				sections.push("", "Fireworks line items may differ from the final invoice once credits or adjustments apply.");
			}
			ctx.ui.notify(sections.join("\n"), "info");
		},
	});
}

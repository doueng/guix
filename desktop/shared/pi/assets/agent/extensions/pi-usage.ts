import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const FIREWORKS_ORIGIN = "https://api.fireworks.ai";
const CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage";
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

type CodexUsage = {
	windows: Array<{ label: string; used: number; resetsAt?: number }>;
	plan?: string;
};

type Entry<T> = {
	query: (ctx: UsageCtx) => Promise<T>;
	render: (data: T) => string;
	describe: (data: T) => string[];
};

type AnyEntry = {
	id: string;
	query: (ctx: UsageCtx) => Promise<unknown>;
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
): Promise<Record<string, unknown>> {
	if (!authorization) throw new Error("no credentials");
	const response = await fetch(url, {
		headers: { Authorization: authorization },
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

async function queryFireworks(ctx: UsageCtx): Promise<FireworksSpend> {
	const authorization = await bearerFromAuth(ctx, PROVIDER_FIREWORKS);
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
	query: queryFireworks,
	render: (spend) => `fw ${formatUsd(spend.total)}`,
	describe: (spend) => [
		...spend.series.map((entry) => `  ${entry.label}: ${formatUsd(entry.amount)}`),
		...(spend.series.length === 0 ? ["  (no rated line items)"] : []),
		`  Accounts: ${spend.accounts.join(", ")}`,
	],
};

async function queryCodex(ctx: UsageCtx): Promise<CodexUsage> {
	const authorization = await bearerFromAuth(ctx, PROVIDER_CODEX);
	const payload = await fetchJson(CODEX_USAGE_URL, authorization, "Codex usage");
	const rateLimit = (payload.rate_limit ?? {}) as Record<string, RateWindow | null>;
	const windows: CodexUsage["windows"] = [];
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

const codexEntry: Entry<CodexUsage> = {
	query: queryCodex,
	render: (usage) => `codex ${usage.windows.map((w) => `${w.label} ${Math.round(w.used)}%`).join(" · ")}`,
	describe: (usage) => [
		`  Plan: ${usage.plan ?? "unknown"}`,
		...usage.windows.map((w) => `  ${w.label} window: ${Math.round(w.used)}% used${resetLabel(w.resetsAt)}`),
	],
};

function asEntry<T>(id: string, entry: Entry<T>): AnyEntry {
	return {
		id,
		query: (ctx) => entry.query(ctx),
		render: (data) => entry.render(data as T),
		describe: (data) => entry.describe(data as T),
	};
}

export default function piUsageExtension(pi: ExtensionAPI) {
	const entries: AnyEntry[] = [
		asEntry(PROVIDER_FIREWORKS, fireworksEntry),
		asEntry(PROVIDER_CODEX, codexEntry),
	];
	const cache = new Map<string, CacheSlot>();
	const inFlight = new Map<string, Promise<unknown>>();

	const publishStatus = (ctx: UsageCtx) => {
		const current = entries.find(({ id }) => id === ctx.model?.provider);
		const visible = current ? [current] : entries;
		const parts: string[] = [];
		for (const { id, render } of visible) {
			const slot = cache.get(id);
			if (slot?.data !== undefined) parts.push(render(slot.data));
			else if (slot?.error) parts.push(`${id}: ${slot.error.slice(0, 40)}`);
		}
		ctx.ui?.setStatus(STATUS_ID, parts.length > 0 ? parts.join(" · ") : undefined);
	};

	const refreshOne = async (ctx: UsageCtx, { id, query }: AnyEntry, force: boolean): Promise<void> => {
		const slot = cache.get(id);
		if (!force && slot && Date.now() - slot.at < TTL_MS) return;
		if (inFlight.has(id)) return inFlight.get(id)?.then(() => undefined);
		const load = query(ctx)
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
		const active: AnyEntry[] = [];
		for (const entry of entries) {
			if ((await bearerFromAuth(ctx, entry.id)) !== undefined) active.push(entry);
		}
		await Promise.all(active.map((entry) => refreshOne(ctx, entry, force)));
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
		description: "Show provider usage (Fireworks spend, Codex limits)",
		handler: async (_args, ctx) => {
			ctx.ui.setStatus(STATUS_ID, "usage checking…");
			await refreshAll(ctx, true);
			const sections: string[] = [];
			for (const { id, describe } of entries) {
				const slot = cache.get(id);
				if (slot?.data !== undefined) {
					sections.push(
						`${id === PROVIDER_FIREWORKS ? `Fireworks, last ${FIREWORKS_WINDOW_DAYS} days` : "OpenAI Codex"}:`,
						...describe(slot.data),
					);
				} else if (slot?.error) {
					sections.push(`${id}: ${slot.error}`);
				}
			}
			if (sections.length === 0) {
				sections.push("No usage providers configured (needs Fireworks or Codex credentials).");
			} else if (cache.get(PROVIDER_FIREWORKS)?.data !== undefined) {
				sections.push("", "Fireworks line items may differ from the final invoice once credits or adjustments apply.");
			}
			ctx.ui.notify(sections.join("\n"), "info");
		},
	});
}

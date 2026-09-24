import type { ExtensionAPI, ExtensionContext, ModelSelectEvent, ThinkingLevel } from "@earendil-works/pi-coding-agent";
import { CustomEditor, ModelSelectorComponent, SettingsManager } from "@earendil-works/pi-coding-agent";
import { CURSOR_MARKER, matchesKey, visibleWidth, type Focusable, type Keybindings, type Theme, type TUI } from "@earendil-works/pi-tui";
import path from "node:path";
import os from "node:os";
import fs from "node:fs/promises";
import type { Dirent } from "node:fs";

// =============================================================================
// Modes
// =============================================================================

type ModeName = string;

type ModeSpec = {
	provider?: string;
	modelId?: string;
	thinkingLevel?: ThinkingLevel;
	/**
	 * Optional theme color token to use for the editor border.
	 * If unset, the border color is derived from the (current) thinking level.
	 */
	color?: string;
};

type ModesFile = {
	version: 1;
	currentMode: ModeName;
	modes: Record<ModeName, ModeSpec>;
};

// Only "default" is a forced/built-in mode. Others are just initial suggestions and can be renamed/deleted.
const DEFAULT_MODE_ORDER = ["default"] as const;
const CUSTOM_MODE_NAME = "custom" as const;

function expandUserPath(p: string): string {
	if (p === "~") return os.homedir();
	if (p.startsWith("~/")) return path.join(os.homedir(), p.slice(2));
	return p;
}

function getGlobalAgentDir(): string {
	// Mirror pi-coding-agent's getAgentDir() behavior (best-effort).
	// For the canonical implementation see pi-mono/packages/coding-agent/src/config.ts
	const env = process.env.PI_CODING_AGENT_DIR;
	if (env) return expandUserPath(env);
	return path.join(os.homedir(), ".pi", "agent");
}

function getGlobalModesPath(): string {
	return path.join(getGlobalAgentDir(), "modes.json");
}

function getProjectModesPath(cwd: string): string {
	return path.join(cwd, ".pi", "modes.json");
}

async function fileExists(p: string): Promise<boolean> {
	try {
		await fs.stat(p);
		return true;
	} catch {
		return false;
	}
}

async function ensureDirForFile(filePath: string): Promise<void> {
	await fs.mkdir(path.dirname(filePath), { recursive: true });
}

async function getMtimeMs(p: string): Promise<number | null> {
	try {
		const st = await fs.stat(p);
		return st.mtimeMs;
	} catch {
		return null;
	}
}

function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

function getLockPathForFile(filePath: string): string {
	// Lock file next to the json so it works across processes.
	return `${filePath}.lock`;
}

async function withFileLock<T>(filePath: string, fn: () => Promise<T>): Promise<T> {
	const lockPath = getLockPathForFile(filePath);
	await ensureDirForFile(lockPath);

	const start = Date.now();
	while (true) {
		try {
			const handle = await fs.open(lockPath, "wx");
			try {
				// Best-effort metadata for debugging stale locks.
				await handle.writeFile(
					JSON.stringify({ pid: process.pid, createdAt: new Date().toISOString() }) + "\n",
					"utf8"
				);
			} catch {
				// ignore
			}

			try {
				return await fn();
			} finally {
				await handle.close().catch(() => {});
				await fs.unlink(lockPath).catch(() => {});
			}
		} catch (err: any) {
			if (err?.code !== "EEXIST") throw err;

			// If the lock looks stale (crash), break it.
			try {
				const st = await fs.stat(lockPath);
				if (Date.now() - st.mtimeMs > 30_000) {
					await fs.unlink(lockPath);
					continue;
				}
			} catch {
				// ignore
			}

			if (Date.now() - start > 5_000) {
				// Don't hang the UI forever.
				throw new Error(`Timed out waiting for lock: ${lockPath}`);
			}
			await sleep(40 + Math.random() * 80);
		}
	}
}

async function atomicWriteUtf8(filePath: string, content: string): Promise<void> {
	await ensureDirForFile(filePath);

	const dir = path.dirname(filePath);
	const base = path.basename(filePath);
	const tmpPath = path.join(dir, `.${base}.tmp.${process.pid}.${Math.random().toString(16).slice(2)}`);

	await fs.writeFile(tmpPath, content, "utf8");

	try {
		// POSIX: atomic replace.
		await fs.rename(tmpPath, filePath);
	} catch (err: any) {
		// Windows: rename can't overwrite.
		if (err?.code === "EEXIST" || err?.code === "EPERM") {
			await fs.unlink(filePath).catch(() => {});
			await fs.rename(tmpPath, filePath);
		} else {
			// best-effort cleanup
			await fs.unlink(tmpPath).catch(() => {});
			throw err;
		}
	}
}

function cloneModesFile(file: ModesFile): ModesFile {
	// JSON-based clone is fine here (small, plain data structure).
	return JSON.parse(JSON.stringify(file)) as ModesFile;
}

type ModeSpecPatch = {
	provider?: string | null;
	modelId?: string | null;
	thinkingLevel?: ThinkingLevel | null;
	color?: string | null;
};

type ModesPatch = {
	currentMode?: ModeName;
	modes?: Record<ModeName, ModeSpecPatch | null>;
};

function computeModesPatch(base: ModesFile, next: ModesFile, includeCurrentMode: boolean): ModesPatch | null {
	const patch: ModesPatch = {};

	if (includeCurrentMode && base.currentMode !== next.currentMode) {
		patch.currentMode = next.currentMode;
	}

	const keys = new Set([...Object.keys(base.modes), ...Object.keys(next.modes)]);
	const modesPatch: Record<ModeName, ModeSpecPatch | null> = {};

	for (const k of keys) {
		const a = base.modes[k];
		const b = next.modes[k];

		if (!b) {
			if (a) modesPatch[k] = null;
			continue;
		}
		if (!a) {
			modesPatch[k] = { ...b };
			continue;
		}

		const diff: ModeSpecPatch = {};
		const fields: (keyof ModeSpec)[] = ["provider", "modelId", "thinkingLevel", "color"];
		for (const f of fields) {
			const av = a[f];
			const bv = b[f];
			if (av !== bv) {
				(diff as any)[f] = bv === undefined ? null : bv;
			}
		}
		if (Object.keys(diff).length > 0) {
			modesPatch[k] = diff;
		}
	}

	if (Object.keys(modesPatch).length > 0) {
		patch.modes = modesPatch;
	}

	if (!patch.modes && patch.currentMode === undefined) return null;
	return patch;
}

function applyModesPatch(target: ModesFile, patch: ModesPatch): void {
	if (patch.currentMode !== undefined) {
		target.currentMode = patch.currentMode;
	}

	if (!patch.modes) return;
	for (const [mode, specPatch] of Object.entries(patch.modes)) {
		if (specPatch === null) {
			delete target.modes[mode];
			continue;
		}

		const targetSpec: Record<string, unknown> = ((target.modes[mode] ??= {}) as any) ?? {};
		for (const [k, v] of Object.entries(specPatch)) {
			if (v === null || v === undefined) {
				delete targetSpec[k];
			} else {
				targetSpec[k] = v;
			}
		}
	}
}

function normalizeThinkingLevel(level: unknown): ThinkingLevel | undefined {
	if (typeof level !== "string") return undefined;
	const v = level as ThinkingLevel;
	// Keep the list local to avoid importing internal enums.
	const allowed: ThinkingLevel[] = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];
	return allowed.includes(v) ? v : undefined;
}

function sanitizeModeSpec(spec: unknown): ModeSpec {
	const obj = (spec && typeof spec === "object" ? spec : {}) as Record<string, unknown>;
	return {
		provider: typeof obj.provider === "string" ? obj.provider : undefined,
		modelId: typeof obj.modelId === "string" ? obj.modelId : undefined,
		thinkingLevel: normalizeThinkingLevel(obj.thinkingLevel),
		color: typeof obj.color === "string" ? obj.color : undefined,
	};
}

function createDefaultModes(ctx: ExtensionContext, pi: ExtensionAPI): ModesFile {
	const currentModel = ctx.model;
	const currentThinking = pi.getThinkingLevel();

	const base: ModeSpec = {
		provider: currentModel?.provider,
		modelId: currentModel?.id,
		thinkingLevel: currentThinking,
	};

	return {
		version: 1,
		currentMode: "default",
		modes: {
			// Forced default mode
			default: { ...base },
			// Convenience mode (user can delete/rename)
			fast: { ...base, thinkingLevel: "off" },
		},
	};
}

function ensureDefaultModeEntries(file: ModesFile, ctx: ExtensionContext, pi: ExtensionAPI): void {
	for (const name of DEFAULT_MODE_ORDER) {
		if (!file.modes[name]) {
			const defaults = createDefaultModes(ctx, pi);
			file.modes[name] = defaults.modes[name];
		}
	}

	// "custom" is an overlay mode; never treat it as a valid persisted current mode.
	if (file.currentMode === CUSTOM_MODE_NAME) {
		file.currentMode = "" as any;
	}

	if (!file.currentMode || !(file.currentMode in file.modes) || file.currentMode === CUSTOM_MODE_NAME) {
		const first = Object.keys(file.modes).find((k) => k !== CUSTOM_MODE_NAME);
		file.currentMode = file.modes.default ? "default" : first || "default";
	}
}

async function loadModesFile(filePath: string, ctx: ExtensionContext, pi: ExtensionAPI): Promise<ModesFile> {
	try {
		const raw = await fs.readFile(filePath, "utf8");
		const parsed = JSON.parse(raw) as Record<string, unknown>;
		const currentMode = typeof parsed.currentMode === "string" ? parsed.currentMode : "default";
		const modesRaw = parsed.modes && typeof parsed.modes === "object" ? (parsed.modes as Record<string, unknown>) : {};
		const modes: Record<string, ModeSpec> = {};
		for (const [k, v] of Object.entries(modesRaw)) {
			modes[k] = sanitizeModeSpec(v);
		}
		const file: ModesFile = {
			version: 1,
			currentMode,
			modes,
		};
		ensureDefaultModeEntries(file, ctx, pi);
		return file;
	} catch {
		return createDefaultModes(ctx, pi);
	}
}

async function saveModesFile(filePath: string, data: ModesFile): Promise<void> {
	await atomicWriteUtf8(filePath, JSON.stringify(data, null, 2) + "\n");
}

function orderedModeNames(modes: Record<string, ModeSpec>): string[] {
	// Preserve insertion order from the JSON file.
	// Object key iteration order is stable in modern JS runtimes.
	// NOTE: "custom" is an overlay mode and must not be selectable/persisted.
	return Object.keys(modes).filter((name) => name !== CUSTOM_MODE_NAME);
}

function getModeBorderColor(ctx: ExtensionContext, pi: ExtensionAPI, mode: string): (text: string) => string {
	const theme = ctx.ui.theme;
	const spec = runtime.data.modes[mode];

	// Explicit color override in JSON.
	if (spec?.color) {
		try {
			// Validate early so we don't crash during render.
			theme.getFgAnsi(spec.color as any);
			return (text: string) => theme.fg(spec.color as any, text);
		} catch {
			// fall through to thinking-based colors
		}
	}

	// Default: derive from the current thinking level.
	return theme.getThinkingBorderColor(pi.getThinkingLevel());
}

function formatModeLabel(mode: string): string {
	return mode;
}

async function resolveModesPath(cwd: string): Promise<string> {
	const projectPath = getProjectModesPath(cwd);
	if (await fileExists(projectPath)) return projectPath;
	return getGlobalModesPath();
}

function inferModeFromSelection(ctx: ExtensionContext, pi: ExtensionAPI, data: ModesFile): string | null {
	const provider = ctx.model?.provider;
	const modelId = ctx.model?.id;
	const thinkingLevel = pi.getThinkingLevel();
	if (!provider || !modelId) return null;

	// Only consider persisted/real modes (exclude the overlay "custom").
	const names = orderedModeNames(data.modes);

	const supportsThinking = Boolean(ctx.model?.reasoning);

	// 1) If thinking is supported, require an exact match so modes can differ by thinking level.
	if (supportsThinking) {
		for (const name of names) {
			const spec = data.modes[name];
			if (!spec) continue;
			if (spec.provider !== provider || spec.modelId !== modelId) continue;
			if ((spec.thinkingLevel ?? undefined) !== thinkingLevel) continue;
			return name;
		}
		return null;
	}

	// 2) If thinking is NOT supported by the model, the effective level will always be "off".
	// In that case, treat thinkingLevel differences in modes.json as non-distinguishing.
	const candidates: string[] = [];
	for (const name of names) {
		const spec = data.modes[name];
		if (!spec) continue;
		if (spec.provider !== provider || spec.modelId !== modelId) continue;
		candidates.push(name);
	}
	if (candidates.length === 0) return null;

	// Prefer a candidate that explicitly matches the effective thinking level.
	for (const name of candidates) {
		const spec = data.modes[name];
		if (!spec) continue;
		if ((spec.thinkingLevel ?? "off") === thinkingLevel) return name;
	}

	// Next prefer a candidate with no thinkingLevel configured.
	for (const name of candidates) {
		const spec = data.modes[name];
		if (!spec) continue;
		if (!spec.thinkingLevel) return name;
	}

	return candidates[0] ?? null;
}

type ModeRuntime = {
	filePath: string;
	fileMtimeMs: number | null;
	/**
	 * Snapshot of what we last loaded/synced from disk. Used to compute patches so
	 * multiple running pi processes don't clobber each other's mode edits.
	 */
	baseline: ModesFile | null;
	data: ModesFile;

	/**
	 * Last non-overlay mode. Used as cycle base while in the overlay "custom" mode.
	 */
	lastRealMode: string;

	/**
	 * The effective current mode. Can temporarily be "custom" (overlay),
	 * which is *not* persisted and not selectable via /mode.
	 */
	currentMode: string;
	// guard against feedback loops when we switch model ourselves
	applying: boolean;
};

const runtime: ModeRuntime = {
	filePath: "",
	fileMtimeMs: null,
	baseline: null,
	data: { version: 1, currentMode: "default", modes: {} },
	lastRealMode: "default",
	currentMode: "default",
	applying: false,
};

// Updated by setEditor() when the custom editor is instantiated.
let requestEditorRender: (() => void) | undefined;

async function ensureRuntime(pi: ExtensionAPI, ctx: ExtensionContext): Promise<void> {
	const filePath = await resolveModesPath(ctx.cwd);

	const mtimeMs = await getMtimeMs(filePath);
	const filePathChanged = runtime.filePath !== filePath;
	const fileChanged = filePathChanged || runtime.fileMtimeMs !== mtimeMs;

	if (fileChanged) {
		runtime.filePath = filePath;
		runtime.fileMtimeMs = mtimeMs;

		const loaded = await loadModesFile(filePath, ctx, pi);
		// Normalize/ensure defaults *before* we snapshot baseline so later persistence
		// only reflects explicit user actions ("store").
		ensureDefaultModeEntries(loaded, ctx, pi);
		runtime.data = loaded;
		runtime.baseline = cloneModesFile(runtime.data);

		// Reset overlay when switching projects.
		if (filePathChanged && runtime.currentMode !== CUSTOM_MODE_NAME) {
			runtime.currentMode = runtime.data.currentMode;
			runtime.lastRealMode = runtime.currentMode;
		}
	}

	// If we're not in the overlay "custom" mode, ensure currentMode is valid.
	if (runtime.currentMode !== CUSTOM_MODE_NAME) {
		if (!runtime.currentMode || !(runtime.currentMode in runtime.data.modes)) {
			runtime.currentMode = runtime.data.currentMode;
		}
		if (!runtime.lastRealMode || !(runtime.lastRealMode in runtime.data.modes)) {
			runtime.lastRealMode = runtime.currentMode;
		}
	}
}

async function persistRuntime(pi: ExtensionAPI, ctx: ExtensionContext): Promise<void> {
	if (!runtime.filePath) return;

	// Do not persist currentMode; multiple running pi sessions would fight over it.
	// Instead we infer the mode on startup from the active model + thinking level.
	runtime.baseline ??= cloneModesFile(runtime.data);
	const patch = computeModesPatch(runtime.baseline, runtime.data, false);
	if (!patch) return;

	await withFileLock(runtime.filePath, async () => {
		// Merge our local patch into the latest on disk to avoid clobbering other agents.
		const latest = await loadModesFile(runtime.filePath, ctx, pi);
		applyModesPatch(latest, patch);
		ensureDefaultModeEntries(latest, ctx, pi);
		await saveModesFile(runtime.filePath, latest);

		runtime.data = latest;
		runtime.baseline = cloneModesFile(latest);
		runtime.fileMtimeMs = await getMtimeMs(runtime.filePath);
	});
}

// We cannot reliably read the *current* model immediately after pi.setModel() in the same tick,
// because ctx.model is a snapshot-ish view that is updated via the model_select event.
// Track the last observed model ourselves and use it for overlays / storing.
let lastObservedModel: { provider?: string; modelId?: string } = {};

function getCurrentSelectionSpec(pi: ExtensionAPI, _ctx: ExtensionContext): ModeSpec {
	return {
		provider: lastObservedModel.provider,
		modelId: lastObservedModel.modelId,
		thinkingLevel: pi.getThinkingLevel(),
	};
}

async function storeSelectionIntoMode(pi: ExtensionAPI, ctx: ExtensionContext, mode: string, selection: ModeSpec): Promise<void> {
	// "custom" is an overlay; it is not persisted.
	if (mode === CUSTOM_MODE_NAME) return;

	await ensureRuntime(pi, ctx);

	const existingTarget = runtime.data.modes[mode] ?? {};
	const next: ModeSpec = { ...existingTarget };

	// Only overwrite fields that we can actually observe.
	if (selection.provider && selection.modelId) {
		next.provider = selection.provider;
		next.modelId = selection.modelId;
	}
	if (selection.thinkingLevel) next.thinkingLevel = selection.thinkingLevel;

	runtime.data.modes[mode] = next;
	await persistRuntime(pi, ctx);
}

async function applyMode(pi: ExtensionAPI, ctx: ExtensionContext, mode: string): Promise<void> {
	await ensureRuntime(pi, ctx);

	// "custom" is a runtime-only overlay mode.
	if (mode === CUSTOM_MODE_NAME) {
		runtime.currentMode = CUSTOM_MODE_NAME;
		customOverlay = getCurrentSelectionSpec(pi, ctx);
		if (ctx.hasUI) requestEditorRender?.();
		return;
	}

	const spec = runtime.data.modes[mode];
	if (!spec) {
		if (ctx.hasUI) {
			ctx.ui.notify(`Unknown mode: ${mode}`, "warning");
		}
		return;
	}

	runtime.currentMode = mode;
	runtime.lastRealMode = mode;
	customOverlay = null;

	runtime.applying = true;
	let modelAppliedOk = true;
	try {
		// Apply model
		if (spec.provider && spec.modelId) {
			const m = ctx.modelRegistry.find(spec.provider, spec.modelId);
			if (m) {
				const ok = await pi.setModel(m);
				modelAppliedOk = ok;
				if (!ok && ctx.hasUI) {
					ctx.ui.notify(`No API key available for ${spec.provider}/${spec.modelId}`, "warning");
				}
			} else {
				modelAppliedOk = false;
				if (ctx.hasUI) {
					ctx.ui.notify(`Mode "${mode}" references unknown model ${spec.provider}/${spec.modelId}`, "warning");
				}
			}
		}

		// Apply thinking level
		if (spec.thinkingLevel) {
			pi.setThinkingLevel(spec.thinkingLevel);
		}
	} finally {
		runtime.applying = false;
	}

	// If we couldn't apply the requested model (e.g. missing API key), switch to overlay.
	// We do *not* treat thinking-level clamping as a failure: clamping is expected when
	// switching between models with different thinking capabilities.
	if (!modelAppliedOk) {
		runtime.currentMode = CUSTOM_MODE_NAME;
		customOverlay = getCurrentSelectionSpec(pi, ctx);
	}

	if (ctx.hasUI) {
		requestEditorRender?.();
	}
}

const MODE_UI_CONFIGURE = "Configure modes…";
const MODE_UI_ADD = "Add mode…";
const MODE_UI_BACK = "Back";

const ALL_THINKING_LEVELS: ThinkingLevel[] = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];
const THINKING_UNSET_LABEL = "(don't change)";

function isDefaultModeName(name: string): boolean {
	return (DEFAULT_MODE_ORDER as readonly string[]).includes(name);
}

function isReservedModeName(name: string): boolean {
	return name === CUSTOM_MODE_NAME || name === MODE_UI_CONFIGURE || name === MODE_UI_ADD || name === MODE_UI_BACK;
}

function normalizeModeNameInput(name: string | undefined): string {
	return (name ?? "").trim();
}

function validateModeNameOrError(
	name: string,
	existing: Record<string, ModeSpec>,
	opts?: { allowExisting?: boolean },
): string | null {
	if (!name) return "Mode name cannot be empty";
	if (/\s/.test(name)) return "Mode name cannot contain whitespace";
	if (isReservedModeName(name)) return `Mode name \"${name}\" is reserved`;
	if (!opts?.allowExisting && existing[name]) return `Mode \"${name}\" already exists`;
	return null;
}

async function handleModeChoiceUI(pi: ExtensionAPI, ctx: ExtensionContext, choice: string): Promise<void> {
	// Special behavior: when we're in "custom" and select another mode,
	// offer to either *use* it (switch) or *store* the current custom selection into it.
	if (runtime.currentMode === CUSTOM_MODE_NAME && choice !== CUSTOM_MODE_NAME) {
		const action = await ctx.ui.select(`Mode \"${choice}\"`, ["use", "store"]);
		if (!action) return;

		if (action === "use") {
			await applyMode(pi, ctx, choice);
			return;
		}

		// "store": overwrite target mode with the current overlay selection (keep target color if set)
		await ensureRuntime(pi, ctx);
		const overlay = customOverlay ?? getCurrentSelectionSpec(pi, ctx);
		await storeSelectionIntoMode(pi, ctx, choice, overlay);
		await applyMode(pi, ctx, choice);
		ctx.ui.notify(`Stored ${CUSTOM_MODE_NAME} into \"${choice}\"`, "info");
		return;
	}

	await applyMode(pi, ctx, choice);
}

async function selectModeUI(pi: ExtensionAPI, ctx: ExtensionContext): Promise<void> {
	if (!ctx.hasUI) return;

	while (true) {
		await ensureRuntime(pi, ctx);
		const names = orderedModeNames(runtime.data.modes);
		const choice = await ctx.ui.select(`Mode (current: ${runtime.currentMode})`, [...names, MODE_UI_CONFIGURE]);
		if (!choice) return;

		if (choice === MODE_UI_CONFIGURE) {
			await configureModesUI(pi, ctx);
			continue;
		}

		await handleModeChoiceUI(pi, ctx, choice);
		return;
	}
}

async function configureModesUI(pi: ExtensionAPI, ctx: ExtensionContext): Promise<void> {
	if (!ctx.hasUI) return;

	while (true) {
		await ensureRuntime(pi, ctx);
		const names = orderedModeNames(runtime.data.modes);
		const choice = await ctx.ui.select("Configure modes", [...names, MODE_UI_ADD, MODE_UI_BACK]);
		if (!choice || choice === MODE_UI_BACK) return;

		if (choice === MODE_UI_ADD) {
			const created = await addModeUI(pi, ctx);
			if (created) {
				await editModeUI(pi, ctx, created);
			}
			continue;
		}

		await editModeUI(pi, ctx, choice);
	}
}

async function addModeUI(pi: ExtensionAPI, ctx: ExtensionContext): Promise<string | undefined> {
	if (!ctx.hasUI) return undefined;
	await ensureRuntime(pi, ctx);

	while (true) {
		const raw = await ctx.ui.input("New mode name", "e.g. docs, review, planning");
		if (raw === undefined) return undefined;

		const name = normalizeModeNameInput(raw);
		const err = validateModeNameOrError(name, runtime.data.modes);
		if (err) {
			ctx.ui.notify(err, "warning");
			continue;
		}

		// Default new modes to the current selection so they behave as expected immediately.
		const selection = customOverlay ?? getCurrentSelectionSpec(pi, ctx);
		runtime.data.modes[name] = {
			provider: selection.provider,
			modelId: selection.modelId,
			thinkingLevel: selection.thinkingLevel,
		};
		await persistRuntime(pi, ctx);
		ctx.ui.notify(`Added mode \"${name}\"`, "info");
		return name;
	}
}

async function editModeUI(pi: ExtensionAPI, ctx: ExtensionContext, mode: string): Promise<void> {
	if (!ctx.hasUI) return;

	let modeName = mode;

	while (true) {
		await ensureRuntime(pi, ctx);
		const spec = runtime.data.modes[modeName];
		if (!spec) return;

		const modelLabel = spec.provider && spec.modelId ? `${spec.provider}/${spec.modelId}` : "(no model)";
		const thinkingLabel = spec.thinkingLevel ?? THINKING_UNSET_LABEL;

		const actions = ["Change name", "Change model", "Change thinking level"];
		if (!isDefaultModeName(modeName)) actions.push("Delete mode");
		actions.push(MODE_UI_BACK);

		const action = await ctx.ui.select(
			`Edit mode \"${modeName}\"  model: ${modelLabel}  thinking: ${thinkingLabel}`,
			actions,
		);
		if (!action || action === MODE_UI_BACK) return;

		if (action === "Change name") {
			const renamed = await renameModeUI(pi, ctx, modeName);
			if (renamed) modeName = renamed;
			continue;
		}

		if (action === "Change model") {
			const selected = await pickModelForModeUI(ctx, spec);
			if (!selected) continue;
			spec.provider = selected.provider;
			spec.modelId = selected.modelId;
			runtime.data.modes[modeName] = spec;
			await persistRuntime(pi, ctx);
			ctx.ui.notify(`Updated model for \"${modeName}\"`, "info");

			if (runtime.currentMode === modeName) {
				await applyMode(pi, ctx, modeName);
			}
			continue;
		}

		if (action === "Change thinking level") {
			const level = await pickThinkingLevelForModeUI(ctx, spec.thinkingLevel);
			if (level === undefined) continue;

			if (level === null) {
				delete spec.thinkingLevel;
			} else {
				spec.thinkingLevel = level;
			}

			runtime.data.modes[modeName] = spec;
			await persistRuntime(pi, ctx);
			ctx.ui.notify(`Updated thinking level for \"${modeName}\"`, "info");

			if (runtime.currentMode === modeName) {
				await applyMode(pi, ctx, modeName);
			}
			continue;
		}

		if (action === "Delete mode") {
			const ok = await ctx.ui.confirm("Delete mode", `Delete mode \"${modeName}\"?`);
			if (!ok) continue;

			delete runtime.data.modes[modeName];
			await persistRuntime(pi, ctx);

			if (runtime.currentMode === modeName) {
				runtime.currentMode = CUSTOM_MODE_NAME;
				customOverlay = getCurrentSelectionSpec(pi, ctx);
			}
			if (runtime.lastRealMode === modeName) {
				runtime.lastRealMode = "default";
			}
			requestEditorRender?.();
			ctx.ui.notify(`Deleted mode \"${modeName}\"`, "info");
			return;
		}
	}
}

function renameModesRecord(modes: Record<string, ModeSpec>, oldName: string, newName: string): Record<string, ModeSpec> {
	const out: Record<string, ModeSpec> = {};
	for (const [k, v] of Object.entries(modes)) {
		if (k === oldName) out[newName] = v;
		else out[k] = v;
	}
	return out;
}

async function renameModeUI(pi: ExtensionAPI, ctx: ExtensionContext, oldName: string): Promise<string | undefined> {
	if (!ctx.hasUI) return undefined;

	if (isDefaultModeName(oldName)) {
		ctx.ui.notify(`Cannot rename default mode \"${oldName}\"`, "warning");
		return oldName;
	}

	await ensureRuntime(pi, ctx);

	while (true) {
		const raw = await ctx.ui.input(`Rename mode \"${oldName}\"`, oldName);
		if (raw === undefined) return undefined;

		const newName = normalizeModeNameInput(raw);
		if (!newName || newName === oldName) return oldName;

		const err = validateModeNameOrError(newName, runtime.data.modes);
		if (err) {
			ctx.ui.notify(err, "warning");
			continue;
		}

		runtime.data.modes = renameModesRecord(runtime.data.modes, oldName, newName);
		await persistRuntime(pi, ctx);

		if (runtime.currentMode === oldName) runtime.currentMode = newName;
		if (runtime.lastRealMode === oldName) runtime.lastRealMode = newName;
		requestEditorRender?.();

		ctx.ui.notify(`Renamed \"${oldName}\" → \"${newName}\"`, "info");
		return newName;
	}
}

async function pickModelForModeUI(
	ctx: ExtensionContext,
	spec: ModeSpec,
): Promise<{ provider: string; modelId: string } | undefined> {
	if (!ctx.hasUI) return undefined;

	const settingsManager = SettingsManager.inMemory();
	const currentModel = spec.provider && spec.modelId ? ctx.modelRegistry.find(spec.provider, spec.modelId) : ctx.model;

	const scopedModels: Array<{ model: any; thinkingLevel: string }> = [];

	return ctx.ui.custom<{ provider: string; modelId: string } | undefined>((tui, _theme, _keybindings, done) => {
		const selector = new ModelSelectorComponent(
			tui,
			currentModel,
			settingsManager,
			ctx.modelRegistry as any,
			scopedModels as any,
			(model) => done({ provider: model.provider, modelId: model.id }),
			() => done(undefined),
		);
		return selector;
	});
}

async function pickThinkingLevelForModeUI(
	ctx: ExtensionContext,
	current: ThinkingLevel | undefined,
): Promise<ThinkingLevel | null | undefined> {
	if (!ctx.hasUI) return undefined;

	const defaultValue = current ?? "off";
	const options = [...ALL_THINKING_LEVELS, THINKING_UNSET_LABEL];
	// Prefer the current selection by ordering it first.
	const ordered = [defaultValue, ...options.filter((x) => x !== defaultValue)];

	const choice = await ctx.ui.select("Thinking level", ordered);
	if (!choice) return undefined;
	if (choice === THINKING_UNSET_LABEL) return null;
	if (ALL_THINKING_LEVELS.includes(choice as ThinkingLevel)) return choice as ThinkingLevel;
	return undefined;
}

async function cycleMode(pi: ExtensionAPI, ctx: ExtensionContext, direction: 1 | -1 = 1): Promise<void> {
	if (!ctx.hasUI) return;
	await ensureRuntime(pi, ctx);
	const names = orderedModeNames(runtime.data.modes);
	if (names.length === 0) return;

	// If we're currently in the overlay mode, cycle relative to the last real mode.
	const baseMode = runtime.currentMode === CUSTOM_MODE_NAME ? runtime.lastRealMode : runtime.currentMode;
	const idx = Math.max(0, names.indexOf(baseMode));
	const next = names[(idx + direction + names.length) % names.length] ?? names[0]!;
	await applyMode(pi, ctx, next);
}

// =============================================================================
// Prompt history
// =============================================================================

const MAX_HISTORY_ENTRIES = 100;
const MAX_RECENT_PROMPTS = 30;

interface PromptEntry {
	text: string;
	timestamp: number;
}

const SOFTWARE_CURSOR_RE = /(\x1b_pi:c\x07)?\x1b\[7m([^\x1b]*)\x1b\[0m/;
const CURSOR_INSERT = "\u001b[?25l";
const CURSOR_NORMAL = "\u001b[?25h\u001b[4 q";
const CURSOR_RESTORE = "\u001b[?25h\u001b[2 q";

type VimMode = "normal" | "insert";

interface Pos {
	line: number;
	col: number;
}

function isWordChar(ch: string): boolean {
	return /\w/.test(ch);
}

function nextWordStart(lines: string[], line: number, col: number, count: number): Pos {
	for (let n = 0; n < count; n++) {
		const text = lines[line] ?? "";
		if (col >= text.length) {
			if (line < lines.length - 1) {
				line++;
				col = 0;
				const nextLine = lines[line] ?? "";
				if (nextLine.length === 0 && line < lines.length - 1) continue;
			}
			continue;
		}
		const startIsWord = isWordChar(text[col]!);
		const startIsSpace = text[col] === " ";
		if (startIsSpace) {
			while (col < text.length && text[col] === " ") col++;
		} else {
			if (startIsWord) while (col < text.length && isWordChar(text[col]!)) col++;
			else while (col < text.length && !isWordChar(text[col]!) && text[col] !== " ") col++;
			while (col < text.length && text[col] === " ") col++;
		}
		if (col >= text.length && line < lines.length - 1) {
			line++;
			col = 0;
			const nextText = lines[line] ?? "";
			while (col < nextText.length && nextText[col] === " ") col++;
		}
	}
	return { line, col };
}

function prevWordStart(lines: string[], line: number, col: number, count: number): Pos {
	for (let n = 0; n < count; n++) {
		if (col === 0) {
			if (line > 0) {
				line--;
				col = (lines[line] ?? "").length;
			}
			if (col === 0) continue;
		}
		const text = lines[line] ?? "";
		while (col > 0 && text[col - 1] === " ") col--;
		if (col === 0) continue;
		const prevIsWord = isWordChar(text[col - 1]!);
		if (prevIsWord) while (col > 0 && isWordChar(text[col - 1]!)) col--;
		else while (col > 0 && !isWordChar(text[col - 1]!) && text[col - 1] !== " ") col--;
	}
	return { line, col };
}

function wordEnd(lines: string[], line: number, col: number, count: number): Pos {
	for (let n = 0; n < count; n++) {
		const text = lines[line] ?? "";
		col++;
		if (col >= text.length) {
			if (line < lines.length - 1) {
				line++;
				col = 0;
				const nextText = lines[line] ?? "";
				while (col < nextText.length && nextText[col] === " ") col++;
			} else {
				col = text.length > 0 ? text.length - 1 : 0;
				continue;
			}
		}
		const curText = lines[line] ?? "";
		while (col < curText.length && curText[col] === " ") col++;
		if (col >= curText.length) {
			col = curText.length > 0 ? curText.length - 1 : 0;
			continue;
		}
		const startIsWord = isWordChar(curText[col]!);
		if (startIsWord) while (col + 1 < curText.length && isWordChar(curText[col + 1]!)) col++;
		else while (col + 1 < curText.length && !isWordChar(curText[col + 1]!) && curText[col + 1] !== " ") col++;
	}
	return { line, col };
}

function firstNonBlank(text: string): number {
	for (let i = 0; i < text.length; i++) {
		if (text[i] !== " " && text[i] !== "\t") return i;
	}
	return 0;
}

class PromptEditor extends CustomEditor {
	public modeLabelProvider?: () => string;
	public modeLabelColor?: (text: string) => string;
	public onTab?: () => void | Promise<void>;
	public onReload?: () => void | Promise<void>;
	public onTree?: () => void | Promise<void>;
	private lockedBorder = false;
	private _borderColor?: (text: string) => string;
	protected vimMode: VimMode = "insert";
	private lastCursorShape?: VimMode;
	private count = 0;
	private pendingOperator: "d" | "c" | "y" | null = null;
	private pendingG = false;
	private pendingR = false;
	private register = "";
	private registerLinewise = false;

	constructor(
		tui: ConstructorParameters<typeof CustomEditor>[0],
		theme: ConstructorParameters<typeof CustomEditor>[1],
		keybindings: ConstructorParameters<typeof CustomEditor>[2],
	) {
		super(tui, theme, keybindings);
		delete (this as { borderColor?: (text: string) => string }).borderColor;
		Object.defineProperty(this, "borderColor", {
			get: () => this._borderColor ?? ((text: string) => text),
			set: (value: (text: string) => string) => {
				if (this.lockedBorder) return;
				this._borderColor = value;
			},
			configurable: true,
			enumerable: true,
		});
	}

	lockBorderColor() {
		this.lockedBorder = true;
	}

	public syncCursorShape(force = false): void {
		if (!force && this.lastCursorShape === this.vimMode) return;
		process.stdout.write(this.vimMode === "insert" ? CURSOR_INSERT : CURSOR_NORMAL);
		this.lastCursorShape = this.vimMode;
	}

	public submitSlashCommand(command: string): void {
		this.setText(command);
		this.onSubmit?.(command);
	}

	handleInput(data: string): void {
		this.syncCursorShape();
		if (this.vimMode === "insert") this.handleInsertMode(data);
		else this.handleNormalMode(data);
	}

	private handleInsertMode(data: string): void {
		if (matchesKey(data, "escape")) {
			this.vimMode = "normal";
			this.syncCursorShape();
			if (this.getCursor().col > 0) super.handleInput("\x1b[D");
			return;
		}
		if (matchesKey(data, "tab")) {
			void this.onTab?.();
			return;
		}
		super.handleInput(data);
	}

	private handleNormalMode(data: string): void {
		if (this.pendingR) {
			this.pendingR = false;
			if (data.length === 1 && data.charCodeAt(0) >= 32) this.replaceChar(data);
			return;
		}
		if (this.pendingG) {
			this.pendingG = false;
			if (data === "g") {
				this.gotoLine(0);
				this.resetPending();
			}
			return;
		}
		if (data.length > 1 || data.charCodeAt(0) < 32) {
			if (matchesKey(data, "tab")) {
				void this.onTab?.();
				return;
			}
			this.resetPending();
			super.handleInput(data);
			return;
		}

		const ch = data;
		if (this.count > 0 && ch >= "0" && ch <= "9") {
			this.count = this.count * 10 + parseInt(ch, 10);
			return;
		}
		if (ch >= "1" && ch <= "9" && this.pendingOperator === null) {
			this.count = parseInt(ch, 10);
			return;
		}
		const effectiveCount = Math.max(this.count, 1);

		if (this.pendingOperator !== null) {
			if (ch === this.pendingOperator) {
				this.executeLinewise(this.pendingOperator, effectiveCount);
				this.resetPending();
				return;
			}
			const target = this.resolveMotion(ch, effectiveCount);
			if (target) {
				this.executeOperatorMotion(this.pendingOperator, target);
				this.resetPending();
				return;
			}
			this.resetPending();
			return;
		}

		switch (ch) {
			case "h":
				for (let i = 0; i < effectiveCount; i++) super.handleInput("\x1b[D");
				break;
			case "j":
				for (let i = 0; i < effectiveCount; i++) super.handleInput("\x1b[B");
				break;
			case "k":
				for (let i = 0; i < effectiveCount; i++) super.handleInput("\x1b[A");
				break;
			case "l":
				for (let i = 0; i < effectiveCount; i++) super.handleInput("\x1b[C");
				break;
			case "0":
				super.handleInput("\x01");
				break;
			case "$":
				super.handleInput("\x05");
				break;
			case "^":
				this.gotoFirstNonBlank();
				break;
			case "w":
				this.moveTo(nextWordStart(this.getLines(), this.getCursor().line, this.getCursor().col, effectiveCount));
				break;
			case "b":
				this.moveTo(prevWordStart(this.getLines(), this.getCursor().line, this.getCursor().col, effectiveCount));
				break;
			case "e":
				this.moveTo(wordEnd(this.getLines(), this.getCursor().line, this.getCursor().col, effectiveCount));
				break;
			case "G":
				this.gotoLine(this.getLines().length - 1);
				break;
			case "g":
				this.pendingG = true;
				this.count = effectiveCount;
				return;
			case "i":
				this.vimMode = "insert";
				this.syncCursorShape();
				break;
			case "a":
				super.handleInput("\x1b[C");
				this.vimMode = "insert";
				this.syncCursorShape();
				break;
			case "I":
				super.handleInput("\x01");
				this.vimMode = "insert";
				this.syncCursorShape();
				break;
			case "A":
				super.handleInput("\x05");
				this.vimMode = "insert";
				this.syncCursorShape();
				break;
			case "o":
				this.openLineBelow();
				break;
			case "O":
				this.openLineAbove();
				break;
			case "x":
				for (let i = 0; i < effectiveCount; i++) super.handleInput("\x1b[3~");
				break;
			case "X":
				for (let i = 0; i < effectiveCount; i++) super.handleInput("\x7f");
				break;
			case "D":
				this.deleteToEnd();
				break;
			case "C":
				this.deleteToEnd();
				this.vimMode = "insert";
				break;
			case "J":
				this.joinLines();
				break;
			case "r":
				void this.onReload?.();
				break;
			case "t":
				void this.onTree?.();
				break;
			case "u":
				super.handleInput("\x1f");
				break;
			case "p":
				this.paste(false);
				break;
			case "P":
				this.paste(true);
				break;
			case "d":
			case "c":
			case "y":
				this.pendingOperator = ch;
				this.count = effectiveCount;
				return;
			default:
				break;
		}
		this.resetPending();
	}

	private resetPending(): void {
		this.count = 0;
		this.pendingOperator = null;
		this.pendingG = false;
	}

	private resolveMotion(ch: string, count: number): Pos | null {
		const lines = this.getLines();
		const cur = this.getCursor();
		switch (ch) {
			case "h": return { line: cur.line, col: Math.max(0, cur.col - count) };
			case "l": return { line: cur.line, col: cur.col + count };
			case "j": return { line: Math.min(lines.length - 1, cur.line + count), col: cur.col };
			case "k": return { line: Math.max(0, cur.line - count), col: cur.col };
			case "w": return nextWordStart(lines, cur.line, cur.col, count);
			case "b": return prevWordStart(lines, cur.line, cur.col, count);
			case "e": {
				const pos = wordEnd(lines, cur.line, cur.col, count);
				return { line: pos.line, col: pos.col + 1 };
			}
			case "0": return { line: cur.line, col: 0 };
			case "$": return { line: cur.line, col: (lines[cur.line] ?? "").length };
			case "^": return { line: cur.line, col: firstNonBlank(lines[cur.line] ?? "") };
			default: return null;
		}
	}

	private executeOperatorMotion(op: "d" | "c" | "y", target: Pos): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		let from: Pos, to: Pos;
		if (target.line < cur.line || (target.line === cur.line && target.col < cur.col)) {
			from = target;
			to = cur;
		} else {
			from = cur;
			to = target;
		}
		if (from.line !== to.line) {
			const newLines = [...lines];
			this.register = lines.slice(from.line, to.line + 1).join("\n");
			this.registerLinewise = true;
			if (op === "y") return;
			newLines.splice(from.line, to.line - from.line + 1);
			if (newLines.length === 0) newLines.push("");
			this.performSurgery(newLines, Math.min(from.line, newLines.length - 1), 0);
			if (op === "c") { this.vimMode = "insert"; this.syncCursorShape(); }
			return;
		}
		const lineText = lines[from.line] ?? "";
		this.register = lineText.slice(from.col, to.col);
		this.registerLinewise = false;
		if (op === "y") return;
		const newLines = [...lines];
		newLines[from.line] = lineText.slice(0, from.col) + lineText.slice(to.col);
		this.performSurgery(newLines, from.line, from.col);
		if (op === "c") { this.vimMode = "insert"; this.syncCursorShape(); }
	}

	private executeLinewise(op: "d" | "c" | "y", count: number): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		const startLine = cur.line;
		const endLine = Math.min(startLine + count - 1, lines.length - 1);
		this.register = lines.slice(startLine, endLine + 1).join("\n");
		this.registerLinewise = true;
		if (op === "y") return;
		const newLines = [...lines];
		if (op === "c") {
			newLines.splice(startLine, endLine - startLine + 1, "");
			this.performSurgery(newLines, startLine, 0);
			this.vimMode = "insert";
			this.syncCursorShape();
		} else {
			newLines.splice(startLine, endLine - startLine + 1);
			if (newLines.length === 0) newLines.push("");
			this.performSurgery(newLines, Math.min(startLine, newLines.length - 1), 0);
		}
	}

	private deleteToEnd(): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		const lineText = lines[cur.line] ?? "";
		this.register = lineText.slice(cur.col);
		this.registerLinewise = false;
		const newLines = [...lines];
		newLines[cur.line] = lineText.slice(0, cur.col);
		this.performSurgery(newLines, cur.line, cur.col);
	}

	private paste(before: boolean): void {
		if (!this.register) return;
		const lines = this.getLines();
		const cur = this.getCursor();
		if (this.registerLinewise) {
			const newLines = [...lines];
			const pasteLines = this.register.split("\n");
			const insertAt = before ? cur.line : cur.line + 1;
			newLines.splice(insertAt, 0, ...pasteLines);
			this.performSurgery(newLines, insertAt, 0);
		} else {
			const lineText = lines[cur.line] ?? "";
			const insertCol = before ? cur.col : Math.min(cur.col + 1, lineText.length);
			const newLines = [...lines];
			newLines[cur.line] = lineText.slice(0, insertCol) + this.register + lineText.slice(insertCol);
			this.performSurgery(newLines, cur.line, insertCol + this.register.length);
		}
	}

	private openLineBelow(): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		const newLines = [...lines];
		newLines.splice(cur.line + 1, 0, "");
		this.performSurgery(newLines, cur.line + 1, 0);
		this.vimMode = "insert";
		this.syncCursorShape();
	}

	private openLineAbove(): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		const newLines = [...lines];
		newLines.splice(cur.line, 0, "");
		this.performSurgery(newLines, cur.line, 0);
		this.vimMode = "insert";
		this.syncCursorShape();
	}

	private joinLines(): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		if (cur.line >= lines.length - 1) return;
		const currentLine = lines[cur.line] ?? "";
		const nextLine = (lines[cur.line + 1] ?? "").trimStart();
		const newLines = [...lines];
		newLines[cur.line] = `${currentLine} ${nextLine}`;
		newLines.splice(cur.line + 1, 1);
		this.performSurgery(newLines, cur.line, currentLine.length);
	}

	private replaceChar(ch: string): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		const lineText = lines[cur.line] ?? "";
		if (cur.col >= lineText.length) return;
		const newLines = [...lines];
		newLines[cur.line] = lineText.slice(0, cur.col) + ch + lineText.slice(cur.col + 1);
		this.performSurgery(newLines, cur.line, cur.col);
	}

	private gotoFirstNonBlank(): void {
		const lines = this.getLines();
		const cur = this.getCursor();
		this.moveTo({ line: cur.line, col: firstNonBlank(lines[cur.line] ?? "") });
	}

	private gotoLine(targetLine: number): void {
		this.moveTo({ line: targetLine, col: 0 });
	}

	private moveTo(target: Pos): void {
		this.performSurgery(this.getLines(), target.line, target.col);
	}

	private performSurgery(newLines: string[], targetLine: number, targetCol: number): void {
		this.setText(newLines.join("\n"));
		const lastLine = newLines.length - 1;
		super.handleInput("\x01");
		for (let i = 0; i < lastLine; i++) super.handleInput("\x1b[A");
		for (let i = 0; i < targetLine; i++) super.handleInput("\x1b[B");
		super.handleInput("\x01");
		for (let i = 0; i < targetCol; i++) super.handleInput("\x1b[C");
	}

	render(width: number): string[] {
		const lines = super.render(width);
		const mode = this.modeLabelProvider?.();
		if (!mode) return lines;

		const stripAnsi = (s: string) => s.replace(/\x1b\[[0-9;]*m/g, "");
		const topPlain = stripAnsi(lines[0] ?? "");

		// If the editor is scrolled, the built-in editor renders a scroll indicator on the top border.
		// Preserve it, but still show the mode label.
		const scrollPrefixMatch = topPlain.match(/^(─── ↑ \d+ more )/);
		const prefix = scrollPrefixMatch?.[1] ?? "──";

		let label = formatModeLabel(mode);
		if (this.count > 1) {
			label += ` · ${this.count}_`;
		}

		// Compute how much room we have for the label core (without truncating the prefix).
		const labelLeftSpace = prefix.endsWith(" ") ? "" : " ";
		const labelRightSpace = " ";
		const minRightBorder = 1; // keep at least one border cell on the right
		const maxLabelLen = Math.max(0, width - prefix.length - labelLeftSpace.length - labelRightSpace.length - minRightBorder);
		if (maxLabelLen <= 0) return lines;
		if (label.length > maxLabelLen) label = label.slice(0, maxLabelLen);

		const labelChunk = `${labelLeftSpace}${label}${labelRightSpace}`;

		const remaining = width - prefix.length - labelChunk.length;
		if (remaining < 0) return lines;

		const right = "─".repeat(Math.max(0, remaining));

		const labelColor = this.modeLabelColor ?? ((text: string) => this.borderColor(text));
		lines[0] = this.borderColor(prefix) + labelColor(labelChunk) + this.borderColor(right);
		return lines;
	}

	public requestRenderNow(): void {
		this.tui.requestRender();
	}

}

function extractText(content: Array<{ type: string; text?: string }>): string {
	return content
		.filter((item) => item.type === "text" && typeof item.text === "string")
		.map((item) => item.text ?? "")
		.join("")
		.trim();
}

function collectUserPromptsFromEntries(entries: Array<any>): PromptEntry[] {
	const prompts: PromptEntry[] = [];

	for (const entry of entries) {
		if (entry?.type !== "message") continue;
		const message = entry?.message;
		if (!message || message.role !== "user" || !Array.isArray(message.content)) continue;
		const text = extractText(message.content);
		if (!text) continue;
		const timestamp = Number(message.timestamp ?? entry.timestamp ?? Date.now());
		prompts.push({ text, timestamp });
	}

	return prompts;
}

function getSessionDirForCwd(cwd: string): string {
	const safePath = `--${cwd.replace(/^[/\\]/, "").replace(/[/\\:]/g, "-")}--`;
	return path.join(getGlobalAgentDir(), "sessions", safePath);
}

async function readTail(filePath: string, maxBytes = 256 * 1024): Promise<string> {
	let fileHandle: fs.FileHandle | undefined;
	try {
		const stats = await fs.stat(filePath);
		const size = stats.size;
		const start = Math.max(0, size - maxBytes);
		const length = size - start;
		if (length <= 0) return "";

		const buffer = Buffer.alloc(length);
		fileHandle = await fs.open(filePath, "r");
		const { bytesRead } = await fileHandle.read(buffer, 0, length, start);
		if (bytesRead === 0) return "";
		let chunk = buffer.subarray(0, bytesRead).toString("utf8");
		if (start > 0) {
			const firstNewline = chunk.indexOf("\n");
			if (firstNewline !== -1) {
				chunk = chunk.slice(firstNewline + 1);
			}
		}
		return chunk;
	} catch {
		return "";
	} finally {
		await fileHandle?.close();
	}
}

async function loadPromptHistoryForCwd(cwd: string, excludeSessionFile?: string): Promise<PromptEntry[]> {
	const sessionDir = getSessionDirForCwd(path.resolve(cwd));
	const resolvedExclude = excludeSessionFile ? path.resolve(excludeSessionFile) : undefined;
	const prompts: PromptEntry[] = [];

	let entries: Dirent[] = [];
	try {
		entries = await fs.readdir(sessionDir, { withFileTypes: true });
	} catch {
		return prompts;
	}

	const files = await Promise.all(
		entries
			.filter((entry) => entry.isFile() && entry.name.endsWith(".jsonl"))
			.map(async (entry) => {
				const filePath = path.join(sessionDir, entry.name);
				try {
					const stats = await fs.stat(filePath);
					return { filePath, mtimeMs: stats.mtimeMs };
				} catch {
					return undefined;
				}
			}),
	);

	const sortedFiles = files
		.filter((file): file is { filePath: string; mtimeMs: number } => Boolean(file))
		.sort((a, b) => b.mtimeMs - a.mtimeMs);

	for (const file of sortedFiles) {
		if (resolvedExclude && path.resolve(file.filePath) === resolvedExclude) continue;

		const tail = await readTail(file.filePath);
		if (!tail) continue;
		const lines = tail.split("\n").filter(Boolean);
		for (const line of lines) {
			let entry: any;
			try {
				entry = JSON.parse(line);
			} catch {
				continue;
			}
			if (entry?.type !== "message") continue;
			const message = entry?.message;
			if (!message || message.role !== "user" || !Array.isArray(message.content)) continue;
			const text = extractText(message.content);
			if (!text) continue;
			const timestamp = Number(message.timestamp ?? entry.timestamp ?? Date.now());
			prompts.push({ text, timestamp });
			if (prompts.length >= MAX_RECENT_PROMPTS) break;
		}
		if (prompts.length >= MAX_RECENT_PROMPTS) break;
	}

	return prompts;
}

function buildHistoryList(currentSession: PromptEntry[], previousSessions: PromptEntry[]): PromptEntry[] {
	const all = [...currentSession, ...previousSessions];
	all.sort((a, b) => a.timestamp - b.timestamp);

	const seen = new Set<string>();
	const deduped: PromptEntry[] = [];
	for (const prompt of all) {
		const key = `${prompt.timestamp}:${prompt.text}`;
		if (seen.has(key)) continue;
		seen.add(key);
		deduped.push(prompt);
	}

	return deduped.slice(-MAX_HISTORY_ENTRIES);
}

// Overlay mode state ("custom"). Not selectable, not cycled into.
let customOverlay: ModeSpec | null = null;

let loadCounter = 0;

function historiesMatch(a: PromptEntry[], b: PromptEntry[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i += 1) {
		if (a[i]?.text !== b[i]?.text || a[i]?.timestamp !== b[i]?.timestamp) return false;
	}
	return true;
}

let savedTui: TUI | undefined;

class HardwareCursorPromptEditor extends PromptEditor implements Focusable {
	private _focused = false;
	get focused(): boolean {
		return this._focused;
	}
	set focused(value: boolean) {
		this._focused = value;
		super.focused = value;
		this.syncCursorShape(true);
	}

	override handleInput(data: string): void {
		this.syncCursorShape(true);
		super.handleInput(data);
	}

	override render(width: number): string[] {
		this.syncCursorShape(true);
		const lines = super.render(width).map((line) =>
			line.replace(SOFTWARE_CURSOR_RE, (_match, marker = "", text = "") =>
				this.vimMode === "insert" ? text : marker + text
			)
		);
		if (lines.length <= 2) return lines.slice(0, 1);
		const spacer = " ".repeat(width);
		const body = this.isShowingAutocomplete() ? lines.slice(1) : lines.slice(1, -1);
		return [lines[0], spacer, ...body, spacer];
	}
}

function setEditor(pi: ExtensionAPI, ctx: ExtensionContext, history: PromptEntry[]) {
	ctx.ui.setEditorComponent((tui: TUI, theme: Theme, keybindings: Keybindings) => {
		savedTui = tui;
		tui.setShowHardwareCursor(true);
		const editor = new HardwareCursorPromptEditor(tui, theme, keybindings);
		editor.syncCursorShape(true);
		requestEditorRender = () => editor.requestRenderNow();
		editor.onReload = () => {
			editor.submitSlashCommand("/reload");
		};
		editor.onTree = () => {
			editor.submitSlashCommand("/tree");
		};
		const borderColor = (text: string) => {
			const isBashMode = editor.getText().trimStart().startsWith("!");
			if (isBashMode) {
				return ctx.ui.theme.getBashModeBorderColor()(text);
			}
			return ctx.ui.theme.getThinkingBorderColor(pi.getThinkingLevel())(text);
		};

		editor.borderColor = borderColor;
		editor.lockBorderColor();
		for (const prompt of history) {
			editor.addToHistory?.(prompt.text);
		}
		return editor;
	});
}

function applyEditor(pi: ExtensionAPI, ctx: ExtensionContext) {
	if (!ctx.hasUI) return;

	const sessionFile = ctx.sessionManager.getSessionFile();
	const currentEntries = ctx.sessionManager.getBranch();
	const currentPrompts = collectUserPromptsFromEntries(currentEntries);
	const immediateHistory = buildHistoryList(currentPrompts, []);

	const currentLoad = ++loadCounter;
	const initialText = ctx.ui.getEditorText();
	setEditor(pi, ctx, immediateHistory);

	void (async () => {
		const previousPrompts = await loadPromptHistoryForCwd(ctx.cwd, sessionFile ?? undefined);
		if (currentLoad !== loadCounter) return;
		if (ctx.ui.getEditorText() !== initialText) return;
		const history = buildHistoryList(currentPrompts, previousPrompts);
		if (historiesMatch(history, immediateHistory)) return;
		setEditor(pi, ctx, history);
	})();
}

// =============================================================================
// Extension Export
// =============================================================================

export default function (pi: ExtensionAPI) {
	pi.on("session_shutdown", () => {
		process.stdout.write(CURSOR_RESTORE);
		savedTui?.setShowHardwareCursor(false);
		savedTui = undefined;
	});

	pi.on("session_start", async (_event, ctx) => {
		lastObservedModel = { provider: ctx.model?.provider, modelId: ctx.model?.id };
		await ensureRuntime(pi, ctx);
		customOverlay = null;

		const inferred = inferModeFromSelection(ctx, pi, runtime.data);
		if (inferred) {
			runtime.currentMode = inferred;
			runtime.lastRealMode = inferred;
		} else {
			// No exact match → treat as overlay.
			runtime.currentMode = CUSTOM_MODE_NAME;
			customOverlay = getCurrentSelectionSpec(pi, ctx);
		}

		applyEditor(pi, ctx);
	});


	pi.on("model_select", async (event: ModelSelectEvent, ctx) => {
		// Always track the last observed model for overlay/store correctness.
		lastObservedModel = { provider: event.model.provider, modelId: event.model.id };

		// Skip mode switching triggered by applyMode() itself, otherwise we'd jump to "custom"
		// while we are in the middle of applying a mode.
		if (runtime.applying) return;

		// Manual model changes always go into the overlay "custom" mode.
		await ensureRuntime(pi, ctx);
		if (runtime.currentMode !== CUSTOM_MODE_NAME) {
			runtime.lastRealMode = runtime.currentMode;
		}
		runtime.currentMode = CUSTOM_MODE_NAME;

		customOverlay = {
			provider: event.model.provider,
			modelId: event.model.id,
			thinkingLevel: pi.getThinkingLevel(),
		};

		// Do not persist/select custom.
		if (ctx.hasUI) {
			requestEditorRender?.();
		}
	});

}

import { readFile, stat } from "node:fs/promises";

import { isFileContentInspectionCommand } from "../core/command.js";
import { compactBashResult } from "../core/integrations/compact-bash-result.js";

import type { Pi, PiContext, PiToolResultEvent } from "./pi-types.js";
import { isRecord } from "./pi-types.js";
import { getAutoCompactionEnabled } from "./settings.js";
import { buildTokenjuiceStatusMessage, buildTokenjuiceStatusSnapshot } from "./status.js";
import { showTokenjuiceStatusPanel } from "./status-panel.js";
import {
  buildBypassNotice,
  buildCompactionNotice,
  buildTokenjuiceDetails,
  extractFullOutputPath,
  extractTextContent,
  mergeDetails,
  parseExitCode,
  stripPiBashEpilogue,
} from "./tool-result.js";
import { formatErrorMessage } from "./utils.js";

export type PiExtensionRuntimeConfig = {
  extensionCommand: string;
};

const DEFAULT_MAX_INLINE_CHARS = 1200;
const GENERIC_FALLBACK_MIN_SAVED_CHARS = 120;
const GENERIC_FALLBACK_MAX_RATIO = 0.75;
const MAX_TRUSTED_FULL_OUTPUT_BYTES = 8 * 1024 * 1024;

export function createTokenjuicePiExtension(config: PiExtensionRuntimeConfig) {
  const extensionCommand = config.extensionCommand || "tj";

  return function tokenjuicePiExtension(pi: Pi): void {
    let enabled = true;
    let bypassNext = false;
    let autoCompactEnabled = true;

    function getProjectRoot(ctx: PiContext): string {
      return ctx.sessionManager.getHeader?.()?.cwd || ctx.sessionManager.getCwd?.() || ctx.cwd;
    }

    async function loadFullOutputText(fullOutputPath: string): Promise<string | null> {
      let details;
      try {
        details = await stat(fullOutputPath);
      } catch (error) {
        throw new Error(`tokenjuice failed to stat bash full output file ${fullOutputPath}: ${formatErrorMessage(error)}`);
      }

      if (details.size > MAX_TRUSTED_FULL_OUTPUT_BYTES) {
        return null;
      }

      try {
        return await readFile(fullOutputPath, "utf8");
      } catch (error) {
        throw new Error(`tokenjuice failed to read bash full output file ${fullOutputPath}: ${formatErrorMessage(error)}`);
      }
    }

    function refreshState(ctx: PiContext): void {
      enabled = true;
      const sessionEntries = typeof ctx.sessionManager.getEntries === "function"
        ? ctx.sessionManager.getEntries()
        : ctx.sessionManager.getBranch();
      for (const entry of sessionEntries) {
        if (entry.type === "custom" && entry.customType === "tokenjuice-pi-config") {
          if (isRecord(entry.data) && typeof entry.data.enabled === "boolean") {
            enabled = entry.data.enabled;
          }
        }
      }
      autoCompactEnabled = getAutoCompactionEnabled(getProjectRoot(ctx));
    }

    function persistState(): void {
      pi.appendEntry("tokenjuice-pi-config", { enabled });
    }

    function findLastTokenjuiceDetails(ctx: PiContext): Record<string, unknown> | null {
      const entries = typeof ctx.sessionManager.getEntries === "function"
        ? ctx.sessionManager.getEntries()
        : ctx.sessionManager.getBranch();

      for (let i = entries.length - 1; i >= 0; i -= 1) {
        const entry = entries[i];
        if (!isRecord(entry) || entry.type !== "message") continue;
        const message = isRecord(entry.message) ? entry.message : null;
        if (!message || message.role !== "toolResult") continue;
        const details = isRecord(message.details) ? message.details : null;
        const tokenjuice = details && isRecord(details.tokenjuice) ? details.tokenjuice : null;
        if (tokenjuice && tokenjuice.compacted === true) return tokenjuice;
      }

      return null;
    }

    function formatLastRaw(details: Record<string, unknown>): string {
      const lines = ["tokenjuice last raw artifact"];
      const fields: Array<[string, string]> = [
        ["reducer", "reducer"],
        ["raw artifact id", "rawArtifactId"],
        ["raw artifact path", "rawArtifactPath"],
        ["filtered output path", "filteredTextPath"],
        ["diff path", "diffPath"],
        ["pi full output path", "fullOutputPath"],
      ];

      for (const [label, key] of fields) {
        const value = details[key];
        if (typeof value === "string" && value) lines.push(`${label}: ${value}`);
      }

      const rawChars = typeof details.rawChars === "number" ? details.rawChars : undefined;
      const reducedChars = typeof details.reducedChars === "number" ? details.reducedChars : undefined;
      const savedChars = typeof details.savedChars === "number" ? details.savedChars : undefined;
      if (rawChars !== undefined || reducedChars !== undefined || savedChars !== undefined) {
        lines.push(`chars: raw=${rawChars ?? "?"} reduced=${reducedChars ?? "?"} saved=${savedChars ?? "?"}`);
      }

      lines.push("", "Use the raw artifact path for exact unmodified command output, or `/tj raw-next` before rerunning a command.");
      return lines.join("\n");
    }

    pi.on("session_start", async (_event, ctx) => {
      refreshState(ctx);
    });

    pi.on("session_tree", async (_event, ctx) => {
      refreshState(ctx);
    });

    pi.registerCommand(extensionCommand, {
      description: "Control tokenjuice bash output compaction",
      handler: async (args, ctx) => {
        refreshState(ctx);

        const action = (args || "status").trim().toLowerCase();
        if (action === "status" || action === "") {
          if (ctx.hasUI && typeof ctx.ui.custom === "function") {
            await showTokenjuiceStatusPanel(
              ctx,
              buildTokenjuiceStatusSnapshot(ctx.sessionManager, {
                manualEnabled: enabled,
                autoCompactEnabled,
                bypassNext,
              }),
            );
          } else {
            ctx.ui.notify(buildTokenjuiceStatusMessage(enabled, autoCompactEnabled, bypassNext), "info");
          }
          return;
        }

        if (action === "on") {
          enabled = true;
          persistState();
          if (autoCompactEnabled) {
            ctx.ui.notify("tokenjuice compaction enabled", "info");
          } else {
            ctx.ui.notify("tokenjuice compaction enabled, but pi auto-compaction is disabled by settings", "warning");
          }
          return;
        }

        if (action === "off") {
          enabled = false;
          persistState();
          ctx.ui.notify("tokenjuice compaction disabled", "info");
          return;
        }

        if (action === "raw-next" || action === "bypass-next") {
          bypassNext = true;
          ctx.ui.notify("tokenjuice will bypass the next bash result", "info");
          return;
        }

        if (action === "last-raw" || action === "last") {
          const details = findLastTokenjuiceDetails(ctx);
          if (!details) {
            ctx.ui.notify("tokenjuice has no compacted bash result in the current branch", "info");
            return;
          }

          pi.sendMessage({
            customType: "tokenjuice-last-raw",
            content: formatLastRaw(details),
            display: true,
            details,
          });
          return;
        }

        ctx.ui.notify(`usage: /${extensionCommand} [status|on|off|raw-next|last-raw]`, "warning");
      },
    });

    pi.on("tool_result", async (rawEvent, ctx) => {
      const event = rawEvent as PiToolResultEvent;
      if (event.toolName !== "bash") {
        return undefined;
      }

      refreshState(ctx);

      const shouldBypass = bypassNext;
      if (shouldBypass) {
        bypassNext = false;
      }

      const command = isRecord(event.input) && typeof event.input.command === "string"
        ? event.input.command
        : "";
      if (!enabled || !autoCompactEnabled || !command) {
        return undefined;
      }

      const outputText = extractTextContent(event.content);
      if (!outputText.trim()) {
        return undefined;
      }

      const fullOutputPath = extractFullOutputPath(event.details);
      if (shouldBypass) {
        const bypassText = fullOutputPath ? await loadFullOutputText(fullOutputPath) : null;
        return {
          content: [{ type: "text", text: `${bypassText ?? outputText}\n\n[${buildBypassNotice(fullOutputPath)}]` }],
        };
      }

      if (isFileContentInspectionCommand({ command })) {
        return undefined;
      }

      const trustedFullOutputText = fullOutputPath ? await loadFullOutputText(fullOutputPath) : undefined;
      if (fullOutputPath && trustedFullOutputText === null) {
        return undefined;
      }

      const exitCode = parseExitCode(outputText, Boolean(event.isError));

      let outcome;
      try {
        outcome = await compactBashResult({
          source: "pi",
          command,
          cwd: ctx.cwd,
          visibleText: stripPiBashEpilogue(outputText),
          ...(typeof trustedFullOutputText === "string" ? { trustedFullText: trustedFullOutputText } : {}),
          exitCode,
          maxInlineChars: DEFAULT_MAX_INLINE_CHARS,
          skipInspectionCommands: true,
          minSavedCharsAny: 8,
          genericFallbackMinSavedChars: GENERIC_FALLBACK_MIN_SAVED_CHARS,
          genericFallbackMaxRatio: GENERIC_FALLBACK_MAX_RATIO,
          skipGenericFallbackForCompoundCommands: true,
          metadata: {
            source: "pi-tool-result",
          },
          storeRaw: true,
        });
      } catch (error) {
        throw new Error(`tokenjuice failed to compact bash output: ${formatErrorMessage(error)}`);
      }

      if (outcome.action === "keep") {
        return undefined;
      }

      const tokenjuiceDetails = buildTokenjuiceDetails(outcome.result, fullOutputPath);

      return {
        content: [{ type: "text", text: `${outcome.result.inlineText}\n\n[${buildCompactionNotice(outcome.result, fullOutputPath)}]` }],
        details: mergeDetails(event.details, tokenjuiceDetails),
      };
    });
  };
}

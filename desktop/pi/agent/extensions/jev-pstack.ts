import os from "node:os";
import path from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const TOOL = "jev_classify_task";
const MODE_ENTRY = "poteto-mode";
const TIMEOUT_MS = 20_000;

// poteto-mode's SKILL.md Route map lists these keys; check-pi-port.mjs fails when they differ.
const ROUTES = {
  investigation: "Read-only understanding, explanation, or diagnosis; no implementation is requested.",
  bug_fix: "Existing behavior is defective; reproduce, diagnose, fix, and verify it.",
  feature: "New or changed externally observable behavior is requested.",
  refactor: "Change implementation structure without intentionally changing behavior.",
  performance: "A reported or measured performance problem, or sustained work on one metric.",
  prototype: "An exploratory implementation should answer a question cheaply; production quality is not the goal.",
  review: "Review existing code, a diff, a design, or an implementation without primarily changing it.",
  large_project: "Cross-cutting, multi-stage, or long unattended work that needs decomposition before execution.",
  pr_workflow: "Open, babysit, get green, address review comments on, or land a pull request or stack.",
  session_handoff: "Resume or take over earlier in-flight work, or pause current work so it can be resumed.",
  skill_work: "Write, edit, or evaluate an agent skill, prompt, or workflow instruction.",
  other: "No category clearly fits, or the task is too ambiguous to classify.",
} as const;

type Route = keyof typeof ROUTES;

const schema = Type.Object(
  {
    task: Type.String({ minLength: 1, maxLength: 12_000, description: "The user's new engineering task, quoted as written." }),
  },
  { additionalProperties: false },
);

function skillPath() {
  const agentDir = process.env.PI_CODING_AGENT_DIR || path.join(os.homedir(), ".pi/agent");
  return path.join(agentDir.replace(/^~(?=\/|$)/, os.homedir()), "skills/pstack/poteto-mode/SKILL.md");
}

function recommendation(route: Route, probability: number) {
  if (route === "other" || probability < 0.5) {
    return "No strong route. Classify the task yourself with poteto-mode's Route map and label the route as manual.";
  }
  if (probability >= 0.75) {
    return "High-probability route. Confirm it matches the request, then pick a playbook for it from the Route map.";
  }
  return "Moderate route. Decide yourself whether it fits, then pick a playbook from the Route map.";
}

export default function jevPstackExtension(pi: ExtensionAPI) {
  let active = false;
  let rereadAfterCompaction = false;

  function sync(ctx: ExtensionContext) {
    const tools = pi.getActiveTools().filter((name) => name !== TOOL);
    pi.setActiveTools(active ? [...tools, TOOL] : tools);
    ctx.ui.setStatus(MODE_ENTRY, active ? "poteto" : undefined);
  }

  function setActive(next: boolean, ctx: ExtensionContext) {
    if (active !== next) {
      active = next;
      pi.appendEntry(MODE_ENTRY, { active });
    }
    rereadAfterCompaction = false;
    sync(ctx);
  }

  pi.registerTool({
    name: TOOL,
    label: "Jev task classifier",
    description: "Classify a new poteto-mode task into a pstack route using TypeSafe Jev. Returns advisory probabilities. Do not use for continuations.",
    promptSnippet: "Classify a new poteto-mode task with Jev",
    promptGuidelines: ["Call once per new task when poteto-mode instructions ask for it, and follow the recommendation line in its result."],
    parameters: schema,
    async execute(_toolCallId, params, signal, _onUpdate, ctx) {
      const jev = ctx.modelRegistry.findOfType("classifier", "typesafe", "jev-latest");
      if (!jev) throw new Error("Pi's TypeSafe Jev classifier is unavailable");
      const classification = await ctx.modelRegistry.classify(jev, {
        state: { task: params.task },
        questions: {
          task: {
            type: "choice",
            instructions: "Which engineering workflow best fits the user's current task? Classify the requested work, not incidental words. If the request is a continuation or does not clearly fit, choose other.",
            criteria: ROUTES,
          },
        },
      }, { signal, timeoutMs: TIMEOUT_MS, maxRetries: 1 });
      if (classification.stopReason !== "stop") {
        throw new Error(classification.errorMessage ?? `Jev classification ${classification.stopReason}`);
      }
      const answer = classification.answers.task;
      if (answer?.type !== "choice") throw new Error("TypeSafe returned no task classification");
      if (!Object.hasOwn(ROUTES, answer.choice)) throw new Error("TypeSafe selected an unknown route");
      const route = answer.choice as Route;
      // Pi validates the response shape and numbers; routing still requires every route's probability.
      const probabilities = Object.fromEntries(Object.keys(ROUTES).map((name) => {
        const probability = answer.probabilities[name];
        if (probability === undefined) throw new Error(`TypeSafe returned no probability for ${name}`);
        return [name, probability];
      })) as Record<Route, number>;
      const ranked = Object.entries(probabilities)
        .sort((a, b) => b[1] - a[1])
        .map(([name, probability]) => `${name} ${probability.toFixed(2)}`)
        .join("\n");

      return {
        content: [{
          type: "text",
          text: `Jev suggests ${route} (probability ${probabilities[route].toFixed(2)}; Choice confidence ${answer.confidence.toFixed(2)}).\n${recommendation(route, probabilities[route])}\n\nDistribution:\n${ranked}`,
        }],
        details: { route, probabilities, confidence: answer.confidence },
        usage: classification.usage,
      };
    },
  });

  pi.registerCommand("poteto-mode-off", {
    description: "Turn off poteto-mode for this session",
    handler: async (_args, ctx) => {
      setActive(false, ctx);
      ctx.ui.notify("poteto-mode is off. Start it again with /skill:poteto-mode.", "info");
    },
  });

  pi.on("session_start", async (_event, ctx) => {
    active = false;
    for (const entry of ctx.sessionManager.getBranch()) {
      if (entry.type === "custom" && entry.customType === MODE_ENTRY) {
        active = (entry.data as { active?: unknown } | undefined)?.active === true;
      }
    }
    rereadAfterCompaction = false;
    sync(ctx);
  });

  pi.on("input", async (event, ctx) => {
    if (event.source !== "extension" && /^\/skill:poteto-mode(?:\s|$)/.test(event.text)) setActive(true, ctx);
    return { action: "continue" };
  });

  pi.on("session_compact", async () => {
    if (active) rereadAfterCompaction = true;
  });

  pi.on("before_agent_start", async () => {
    if (!active || !rereadAfterCompaction) return;
    rereadAfterCompaction = false;
    return {
      message: {
        customType: "poteto-mode-reminder",
        content: `poteto-mode is still active, but compaction removed its instructions. Read ${skillPath()} in full before you continue. A new task still starts with codemode: text(await tools.${TOOL}({ task: "<user task>" })).`,
        display: false,
      },
    };
  });
}

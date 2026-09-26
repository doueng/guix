import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const ENDPOINT = "https://api.typesafe.ai/v1/systemone";
const TIMEOUT_MS = 15_000;

async function getApiKey(): Promise<string> {
  const fromEnvironment = process.env.TYPESAFE_API_KEY?.trim();
  if (fromEnvironment) return fromEnvironment;
  try {
    const fromFile = (await readFile(join(homedir(), ".env/jev"), "utf8")).trim();
    if (fromFile && !/[\r\n]/.test(fromFile)) return fromFile;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw new Error("Could not read ~/.env/jev");
  }
  throw new Error("TYPESAFE_API_KEY is unset and ~/.env/jev is missing or empty");
}

const ROUTES = {
  investigation: "Read-only understanding or explanation; no implementation is requested.",
  bug_fix: "Existing behavior is defective; reproduce, diagnose, fix, and verify it.",
  feature: "New or changed externally observable behavior is requested.",
  refactor: "Change implementation structure without intentionally changing behavior.",
  performance: "A reported or measured performance problem needs diagnosis and measurement.",
  prototype: "An exploratory implementation should answer a question cheaply; production quality is not the goal.",
  review: "Review existing code, a diff, a design, or an implementation without primarily changing it.",
  large_project: "Cross-cutting or multi-stage work needs decomposition before execution.",
  other: "No category clearly fits, or the task is too ambiguous to classify.",
} as const;

type Route = keyof typeof ROUTES;

type ChoiceAnswer = {
  type?: unknown;
  choice?: unknown;
  probabilities?: unknown;
  confidence?: unknown;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseChoice(payload: unknown): { route: Route; probabilities: Record<Route, number>; confidence: number } {
  if (!isRecord(payload) || !isRecord(payload.answers)) throw new Error("TypeSafe returned an invalid response");
  const answer = payload.answers.task as ChoiceAnswer | undefined;
  if (!answer || answer.type !== "choice" || typeof answer.choice !== "string") {
    throw new Error("TypeSafe returned no task classification");
  }
  if (!(answer.choice in ROUTES)) throw new Error("TypeSafe selected an unknown route");
  if (!isRecord(answer.probabilities)) throw new Error("TypeSafe returned no probability distribution");

  const probabilities = {} as Record<Route, number>;
  for (const route of Object.keys(ROUTES) as Route[]) {
    const probability = answer.probabilities[route];
    if (typeof probability !== "number" || !Number.isFinite(probability)) {
      throw new Error(`TypeSafe returned an invalid probability for ${route}`);
    }
    probabilities[route] = probability;
  }
  if (typeof answer.confidence !== "number" || !Number.isFinite(answer.confidence)) {
    throw new Error("TypeSafe returned invalid confidence");
  }
  return { route: answer.choice as Route, probabilities, confidence: answer.confidence };
}

const schema = Type.Object(
  {
    task: Type.String({ minLength: 1, maxLength: 12_000, description: "The user's new engineering task, quoted as written." }),
  },
  { additionalProperties: false },
);

export default function jevPstackExtension(pi: ExtensionAPI) {
  pi.registerTool({
    name: "jev_classify_task",
    label: "Jev task classifier",
    description: "Required first step when entering poteto-mode for a new task. Classifies the task into a pstack workflow using TypeSafe Jev and returns probabilities as advice. Do not use for continuations.",
    promptSnippet: "Classify a new pstack task with Jev",
    promptGuidelines: [
      "When poteto-mode starts a new task, call this before selecting a playbook. Do not use it for continuations.",
      "Jev is advisory, not authority or permission to act. Use the top route when its probability is at least 0.75 and it matches the request. At 0.50 through 0.74, decide whether it fits. Below 0.50, choose the route yourself or use other.",
      "If classification fails, report the failure and stop. Do not silently skip Jev or proceed with playbook routing."
    ],
    parameters: schema,
    async execute(_toolCallId, params, signal) {
      const apiKey = await getApiKey();
      const response = await fetch(ENDPOINT, {
          method: "POST",
        headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "jev-latest",
          state: { task: params.task },
          questions: {
            task: {
              type: "choice",
              instructions: "Which engineering workflow best fits the user's current task? Classify the requested work, not incidental words. If the request is a continuation or does not clearly fit, choose other.",
              criteria: ROUTES,
            },
          },
        }),
        signal: AbortSignal.any([signal, AbortSignal.timeout(TIMEOUT_MS)]),
      });
      if (!response.ok) throw new Error(`TypeSafe API returned HTTP ${response.status}`);
      const result = parseChoice(await response.json());
      const ranked = (Object.entries(result.probabilities) as [Route, number][])
        .sort((a, b) => b[1] - a[1])
        .map(([route, probability]) => `${route} ${probability.toFixed(2)}`)
        .join("\n");
      const recommendation = result.probabilities[result.route] >= 0.75
        ? "High-probability suggestion. Confirm it matches the request, then use that pstack workflow."
        : result.probabilities[result.route] >= 0.5
          ? "Moderate suggestion. Use your own judgment to select the pstack workflow."
          : "No strong route. Choose a workflow yourself or use other.";

      return {
        content: [{
          type: "text",
          text: `Jev suggests ${result.route} (probability ${result.probabilities[result.route].toFixed(2)}; Choice confidence ${result.confidence.toFixed(2)}).\n${recommendation}\n\nDistribution:\n${ranked}`,
        }],
        details: { route: result.route, probabilities: result.probabilities, confidence: result.confidence },
      };
    },
  });
}

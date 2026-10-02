import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const TIMEOUT_MS = 15_000;

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
      }, { signal, timeoutMs: TIMEOUT_MS, maxRetries: 0 });
      if (classification.stopReason !== "stop") {
        throw new Error(classification.errorMessage ?? `Jev classification ${classification.stopReason}`);
      }
      const answer = classification.answers.task;
      if (answer?.type !== "choice") throw new Error("TypeSafe returned no task classification");
      if (!Object.hasOwn(ROUTES, answer.choice)) throw new Error("TypeSafe selected an unknown route");
      // Pi validates the response shape and numbers; routing still requires every route's probability.
      const probabilities = Object.fromEntries(Object.keys(ROUTES).map((route) => {
        const probability = answer.probabilities[route];
        if (probability === undefined) throw new Error(`TypeSafe returned no probability for ${route}`);
        return [route, probability];
      }));
      const result = { route: answer.choice, probabilities, confidence: answer.confidence };
      const ranked = Object.entries(result.probabilities)
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
        details: result,
        usage: classification.usage,
      };
    },
  });
}

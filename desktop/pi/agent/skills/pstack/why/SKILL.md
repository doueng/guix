---
name: why
description: "Investigate why code or a design took its current shape. Trace rationale, constraints, tradeoffs, regressions, and thresholds. Use how for runtime behavior."
disable-model-invocation: true
---

# Why

Investigate the motivation and intent behind code. `how` answers what code does and how it works. `why` answers what forces led to its shape.

## 1. Understand the question

Identify the target, the rationale question, and the evidence categories that could answer it. If the target is vague, state the likely interpretation and proceed from available context.

## 2. Anchor in code and history

Find the relevant paths, lines, symbols, and recent history. Use the repository's actual version-control tool. On Git repositories, useful commands include `git blame`, `git log --follow -p`, and `gh pr view`. Do not assume the repository uses Git or has a GitHub remote.

## 3. Search evidence

Search available sources that match the question, such as source history, issues, design documents, team discussion, observability, error tracking, and product data. Use only tools actually available in the current Pi session. A missing source is a gap, not a negative result. Skip a source only when it is unavailable or demonstrably irrelevant, and say which.

If a local delegation workflow is available, assign independent evidence categories to separate read-only investigators. Otherwise use codemode to batch independent read-only searches with `Promise.allSettled()` and return relevant evidence with source identifiers. Keep dependent searches sequential. Do not claim a source or investigator was consulted without evidence.

## 4. Synthesize

Separate direct evidence from inference and speculation. Cite the paths, commits, tickets, or other artifacts that support each claim. Include competing explanations and what remains unknown.

## Output

Use the structure in `references/synthesizer-prompt.md`: The Question, The Code in Question, What We Found, What We Can Reasonably Infer, Competing Hypotheses, What We Don't Know, Sources Consulted, and Confidence Summary. Adapt sections as needed, but keep evidence and confidence separate.

If the question precedes a code change, finish with Preserve / Change / Avoid / Risk constraints.

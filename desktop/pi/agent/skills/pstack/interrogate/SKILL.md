---
name: interrogate
description: "Run adversarial review when requested or when a design needs independent challenge."
disable-model-invocation: true
---

# Interrogate

Produce an adversarial review of a change without applying fixes automatically. Use independent reviewers only when a delegation mechanism is available in the current Pi environment. Otherwise conduct a structured self-review and label it as such.

## Review

1. Identify the exact diff and its intent. Read surrounding code and the review rubric in `references/rubric.md`.
2. If independent review is available, partition reviewers by distinct concerns and provide each the same scope and rubric. Keep reviewers read-only.
3. Inspect each report against the actual code. Deduplicate findings, retain supported lone findings, and record disagreements.
4. Categorize findings as act on, consider, noted, or dismissed. Explain why each category fits.

## Output

### Intent

> One paragraph describing what the change should accomplish.

### Reviewers

State who or what performed the review. Do not imply independent reviewers ran if this was a self-review.

### Findings

List actionable findings with evidence and severity. Summarize lower-priority and dismissed findings with reasons.

### Coverage

Name unreviewed areas and any limits to the evidence.

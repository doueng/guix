---
name: reflect
description: Review the current conversation for durable learnings and route each supported lesson to a concrete skill or tooling change.
disable-model-invocation: true
---

# Reflect

Review the current conversation for durable process lessons, then propose a small skill or tooling change. Skip trivial conversations and one-off preferences.

## Process

1. Find the current Pi session transcript under `~/.pi/agent/sessions/` when available. Confirm it matches this conversation. If unavailable, use a concise digest of the visible conversation and state the evidence limit.
2. Identify repeated friction, a corrected assumption, or a missing workflow. Distinguish a one-off from a repeatable lesson.
3. If independent reviewers can be run with an available local delegation mechanism, give them distinct review lenses and read their reports. Otherwise perform those lenses sequentially and label them self-review.
4. Check whether the lesson belongs in an existing skill, a new skill, or an enforceable script or lint. Prefer structure for rules that can be checked mechanically.
5. Present proposed changes and rejected alternatives. Apply changes only when requested or already authorized.
6. Validate each edited skill and report the path and evidence.

Use project skills under `desktop/pi/agent/skills/` and personal skills under `~/.pi/agent/skills/`. Do not assume a particular model configuration, review service, backlog tracker, or skill-authoring plugin.

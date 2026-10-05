---
name: reflect
description: Review the current conversation for durable learnings and route each supported lesson to a concrete skill or tooling change.
disable-model-invocation: true
---

# Reflect

Review the current conversation for durable process lessons, then propose a small skill or tooling change. Skip trivial conversations and one-off preferences.

## Process

1. Prefer the current transcript path from active Pi session metadata. Otherwise run `node ../poteto-mode/scripts/session-dir.mjs <workspace>` relative to this skill directory, passing any explicit CLI session directory. Confirm the transcript's workspace and conversation before reading messages. Never inspect unrelated workspaces. If unavailable, use a concise digest of the visible conversation and state the evidence limit.
2. Identify repeated friction, a corrected assumption, or a missing workflow. Distinguish a one-off from a repeatable lesson.
3. If independent reviewers can be run with an available local delegation mechanism, give them distinct review lenses and read their reports. Otherwise perform those lenses sequentially and label them self-review.
4. Check whether the lesson belongs in an existing skill, a new skill, or an enforceable script or lint. Prefer structure for rules that can be checked mechanically.
5. Present proposed changes and rejected alternatives. Apply changes only when requested or already authorized.
6. Validate each edited skill and report the path and evidence.

Use project skills under `.pi/skills/` or `.agents/skills/` and personal skills under `~/.pi/agent/skills/`. Edit a symlinked skill at its repository source, preserving the installed link. Do not assume a particular model configuration, review service, backlog tracker, or skill-authoring plugin.

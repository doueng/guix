---
name: retro
description: "Run a retrospective on a Pi session and propose ranked changes to the agent's environment: SCI codemode, checks, guidance, tools and information access. Use when the user asks for a retro, usually at the end of a conversation."
disable-model-invocation: true
---

# Retro

Find what made the session slow or wrong, and propose the environment change that would have prevented it. Present the candidates. Apply them only when the user asks.

## Gather evidence

Default to the current session. Work from primary sources, not memory of the conversation.

1. **Transcript.** The session file is the newest `.jsonl` in `~/.pi/agent/sessions/<cwd with each / replaced by ->/`. Read it with `fs/read-jsonl`, project `type`, `message.role`, `message.content`, `message.isError` and `message.toolName`, and follow `:next_cursor` to the end. The projection shape is in the structured JSON section of `~/guix/desktop/build/pi-sci/domain-commands.md`. In SCI, extract each codemode `:code`, every error result, and every user correction.
2. **Metrics.** Run `node ~/guix/desktop/build/pi-sci/scripts/session-metrics.mjs <session.jsonl>`. It counts bash escapes, validation errors, parse errors and truncation.
3. **Vent log.** Read `VENT.md` in the workspace. A candidate that repeats an entry ranks higher. A recurrence of an entry marked resolved is a regression.

## Find candidates

Cite the call index, error text or user message behind every candidate.

- **Wrong outcomes.** A wrong claim, a broken build, or a change that failed on the real machine. Name the check that, run before the agent declared done, would have failed. These rank first.
- **Bash escapes.** Group the bash commands by purpose. A purpose repeated three or more times, or one done by grepping generated files, needs a semantic operation, a repo script or a Make target.
- **Codemode errors.** For each validation error, unresolved symbol or retry, check whether one retry fixed it. If not, the error message or tool description is the fix.
- **Navigation.** Calls spent locating files, sources or session paths. A pointer in the nearest skill or `AGENTS.md`, or a helper script, fixes these.
- **Information access.** Facts the agent could not observe, such as root-only logs or service state, and how it worked around them.
- **Guidance.** Instructions the agent violated, and instructions that changed nothing. Move mechanical rules into checks and delete no-ops.

Before proposing a check or operation, search for an existing one. An existing check that is unwired or broken is the finding.

## Choose the fix

Pick the strongest mechanism that works, per `~/.pi/agent/skills/pstack/principle-encode-lessons-in-structure/SKILL.md`. From strongest to weakest:

1. Configuration or types that make the mistake impossible.
2. A check wired into `make check`, a test, or the tool itself.
3. A semantic codemode operation, repo script or Make target.
4. A clearer error message, hint or tool description.
5. A pointer in a skill or `AGENTS.md`.

Write prose guidance only for judgement calls.

Fixes land in these places:

- SCI codemode operations, errors and hints are in `~/guix/desktop/build/pi-sci/` (see its README). Verify with `make pi-sci-test`.
- Global agent guidance is `~/guix/desktop/pi/agent/AGENTS.md`. Pi extensions are in `~/guix/desktop/pi/agent/extensions/`.
- Repository guidance is the repo's `AGENTS.md` and `.agents/skills/`.
- Guix, Pi and the vendored `pstack` skills are upstream. Propose those changes. Do not edit vendored files.

## Report

List candidates by severity: wrong outcomes, then costs repeated across sessions, then one-off costs. For each, give the evidence, the change with its file path, the mechanism level, and the expected effect. Then list dismissed candidates with a reason, and state what you did not read.

If the user approves candidates, apply each as its own jj change and verify it. Record any approved candidate you did not apply with the `vent` tool, so the next retro finds it.

---
name: recall
description: Reconstruct recent work from available session history, live repository state, and shared records. Use for a catch-up or to resume work when the user has not supplied a full state capsule.
disable-model-invocation: true
---

# Recall

Rebuild the relevant working context and return a concise capsule of current status and next actions.

## Process

1. Prefer session paths from active Pi metadata. Otherwise run `node ../poteto-mode/scripts/session-dir.mjs <workspace>` relative to this skill directory, passing any explicit CLI session directory as the second argument. Confirm each transcript's workspace and conversation before reading messages. If session files are unavailable, say so.
2. Set the time window, topic, and workspace before searching. Default to the active workspace and recent history. Do not search another workspace without permission.
3. Search relevant conversations for goals, decisions, open threads, corrections, and artifacts. For small searches, do this directly. If local delegation is available and the corpus is large, divide independent time slices and verify the reports against source transcripts. Otherwise search sequentially.
4. For a named feature or bug, check available source history, issues, discussions, and incident records. Use only tools available in the current Pi session. Missing sources are explicit gaps.
5. Verify reported PRs, branches, and issues against live repository or forge state. When a claim depends on what an agent did, inspect the actual transcript if available.
6. Return a short, evidence-linked brief. Exclude adjacent work unless it blocks the target.

## Output

- **Capsule.** At most five bullets describing the work and overall status.
- **Threads.** One line per workstream with an explicit state and supporting identifier.
- **Problems.** At most five recurring problems, including failed or reverted attempts when evidenced.
- **Next move.** The single most useful concrete action.

# pstack for Pi

This bundle adapts the MIT-licensed pstack engineering workflows for Pi. Skill files and supporting references stay together so Pi can discover nested `SKILL.md` files and resolve relative references.

Use `/skill:poteto-mode` as the explicit entry point. The bundle's skills disable automatic model invocation, so poteto-mode resolves sibling skills as `../<name>/SKILL.md`. The `jev-pstack.ts` extension stands in for the upstream persistent mode. It records the mode in the session, activates `jev_classify_task` only while the mode is on, and asks the model to re-read poteto-mode after compaction. Pi has no built-in subagent API. Use delegation only through an explicitly requested and available local workflow; otherwise work directly and state that parallel review was unavailable. Use Pi's active session and configuration paths rather than assuming another agent host's layout.

## Provenance

Upstream repository is https://github.com/cursor/plugins. The current baseline is pstack 0.15.13 at `2cbf58508f40de470d7490b55c51d71241928fa2`, in the `pstack` plugin directory. The previous baseline was 0.15.5 at `ecc249f1e306fc64ddf83c7bed16cacf7c2239db`. The original import did not record its exact revision.

The 0.15.5 to 0.15.13 delta was applied with these adaptations.

- Imported `benchmark-checklist`, `correct`, and `principle-explain-the-number` unchanged.
- Applied upstream text unchanged to the `architect` red-flag screening line, `design-red-flags.md`, `hillclimb.md` steps 2 and 4, `perf-issue.md` steps 1 and 2, the `opening-a-pr.md` description and PR-tool rules, `technical-writing`, and `typescript-best-practices/references/patterns.md`.
- Adapted the fresh-agent rule into poteto-mode's Delegation section and `poteto-agent.md`. Adapted push-after-every-unit and the pre-merge drift checks into the Pi autopilot playbooks.
- Adapted the hourly audit cadence into `multi-phase-plan.md` and `check-plan.mjs` without `/loop` or `/goal`.

Omitted changes, with reasons.

- `poteto-help` and `docs/guide/`. They document the upstream host's installation, custom modes, `/setup-pstack`, and model rules, none of which exist in Pi.
- `swarm` "respawn" wording. The Pi swarm skill was rewritten without the retry sentence it edits.
- `/goal` and `/loop 1h` arming in the autopilot and multi-phase playbooks. They are upstream host commands. The Pi playbooks keep their session-bound monitoring rule.
- Host-specific PR tooling stays generic. `opening-a-pr.md` names a run's built-in PR tool only when one exists.

Local changes include Pi entry commands, Jev routing with a manual fallback, the route map, mode persistence, conditional delegation, read-only self-review, session-directory resolution, GNU-system audit support, codemode-based MCP discovery and calls, batched read-only evidence gathering, and port validation. Keep the MIT license with the bundle.

## Runtime and paths

Guix Home's development packages provide Node and the checksum-pinned Bun 1.3.14 ARM64 release. The watcher and orchestration ledger bootstrap their npm dependencies using their own frozen `bun.lock`. Run `bun install --frozen-lockfile` in `poteto-mode/scripts/` before tests.

Project skills belong in `.pi/skills/` or `.agents/skills/`. Personal skills belong in the configured Pi agent directory's `skills/`. Stow installs this bundle under `~/.pi/agent/skills/pstack/`. Resolve scripts relative to the skill location rather than the current repository. To find transcripts, prefer active Pi session metadata. The `poteto-mode/scripts/session-dir.mjs` helper resolves environment and settings overrides without reading transcript messages. An explicit CLI session directory must be supplied to the helper. Confirm workspace and conversation identity before reading any transcript.

## Updating

1. Refresh the upstream checkout through the librarian workflow. Choose and record a specific source commit and plugin version.
2. Compare upstream `pstack/` changes since the baseline. Apply selected changes to the repository source under `desktop/pi/agent/skills/pstack/`. Preserve the local adaptations above. Do not copy host-specific agents, model rules, or automation into Pi unchanged.
3. Update this baseline only after reconciling the selected upstream delta. List applied adaptations and omitted changes under Provenance.
4. In `poteto-mode/scripts/`, run `bun install --frozen-lockfile`, `bun run test`, and `bun run typecheck`. Run `shellcheck worktree-audit.sh`. Port validation checks bundled references and runtime availability.
5. Refresh installed links with `stow --dir=desktop/pi --target="$HOME/.pi/agent" --no-folding --ignore=node_modules --restow agent`. Run Pi's `/reload` and verify `/skill:poteto-mode` is available. Do not activate Guix Home just to refresh skill files.

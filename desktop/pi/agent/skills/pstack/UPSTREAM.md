# pstack for Pi

This bundle adapts the MIT-licensed pstack engineering workflows for Pi. Skill files and supporting references stay together so Pi can discover nested `SKILL.md` files and resolve relative references.

New sessions start in poteto-mode: the `jev-pstack.ts` extension rewrites the first plain prompt as `/skill:poteto-mode <prompt>`. Use `/skill:poteto-mode` as the explicit entry point elsewhere, and `/poteto-mode-off` to opt out. The bundle's skills disable automatic model invocation, so poteto-mode resolves sibling skills as `../<name>/SKILL.md`. The `jev-pstack.ts` extension stands in for the upstream persistent mode. It records the mode in the session, activates `jev_classify_task` only while the mode is on, and asks the model to re-read poteto-mode after compaction. Pi has no built-in subagent API. Use delegation only through an explicitly requested and available local workflow; otherwise work directly and state that parallel review was unavailable. Use Pi's active session and configuration paths rather than assuming another agent host's layout.

## Provenance

Upstream repository is https://github.com/cursor/plugins. The current baseline is pstack 0.15.36 at `c742dd61706fbacc1b95f1a20b7b988cf096ce6c`, in the `pstack` plugin directory. The previous baseline was 0.15.13 at `2cbf58508f40de470d7490b55c51d71241928fa2`. The original import did not record its exact revision.

The 0.15.13 to 0.15.36 delta was applied with these adaptations.

- Took upstream text unchanged for every `principle-*` skill and for `benchmark-checklist`, `blast-radius`, `correct`, `technical-writing`, `typescript-best-practices`, and `unslop`. `figure-it-out` drops its pointer to poteto-mode's Subagents section, which the local poteto-mode lacks.
- Rewrote `interrogate` to upstream's run-proven findings and deleted its references. Adversaries run through bbh `delegate` or play in turn when no delegation exists.
- Imported `ketchup` as text only, and `maintain-formal-verification-skill` with jj change ids as sync points.
- Mapped the restate-the-goal trigger, the proof-backed blocking rule, and ketchup's decidable action items onto bbh super: the Worker and Router prompts in `desktop/configs/bbh/init.clj`, the reviewer brief in `bbh.workflow/review!`, and the Reply section of poteto-mode.

Omitted changes, with reasons.

- `statechart`, about 20k lines of TypeScript that need Java and TLC through mise. The project-local TLA+ check already covers bbh.
- `ketchup-video`, `sharpen`, `in-your-own-words` as a file, `poteto-help`, `setup-pstack`, `make-bot-ui`, `docs/`, and the plugin manifests. They are host-specific, render video, or have no bbh hook.
- Subagent model rules (matching effort to the job, slug fallbacks, background steers). They name upstream host models and the Task tool.
- The playbooks, including Orchestrate without the orch scripts, autopilot, shipping, babysit, opening-a-pr, multi-phase, watch-pr, and worktree-audit. The local poteto-mode has no playbooks; bbh super and the `wf` workflows replace them.
- Distillations of `how`, `why`, `arena`, `swarm`, `reflect`, `architect`, `recall`, and `show-me-your-work`. The local copies are Pi rewrites.

Local changes include Pi entry commands, Jev routing with a manual fallback, the route map, mode persistence, conditional delegation, read-only self-review, session-directory resolution, GNU-system audit support, codemode-based MCP discovery and calls, batched read-only evidence gathering, port validation, and `show-me-your-work/scripts/log.bb`, a babashka port of upstream `log.sh` because this repository allows no shell scripts. Keep the MIT license with the bundle.

## Runtime and paths

Guix Home's development packages provide Node and the checksum-pinned Bun 1.3.14 ARM64 release. The TypeScript watcher and orchestration ledger were replaced by the bb workflow engine in `desktop/build/bbh`, so this bundle has no npm dependencies.

Project skills belong in `.pi/skills/` or `.agents/skills/`. Personal skills belong in the configured Pi agent directory's `skills/`. Stow installs this bundle under `~/.pi/agent/skills/pstack/`. Resolve scripts relative to the skill location rather than the current repository. To find transcripts, prefer active Pi session metadata. The `wf session-dir` command resolves environment and settings overrides without reading transcript messages. An explicit CLI session directory must be supplied to the helper. Confirm workspace and conversation identity before reading any transcript.

## Updating

1. Refresh the upstream checkout through the librarian workflow. Choose and record a specific source commit and plugin version.
2. Compare upstream `pstack/` changes since the baseline. Apply selected changes to the repository source under `desktop/pi/agent/skills/pstack/`. Preserve the local adaptations above. Do not copy host-specific agents, model rules, or automation into Pi unchanged.
3. Update this baseline only after reconciling the selected upstream delta. List applied adaptations and omitted changes under Provenance.
4. Run `cd desktop/build/bbh && bb test:workflow`. Port validation checks bundled references and runtime availability.
5. Refresh installed links with `stow --dir=desktop/pi --target="$HOME/.pi/agent" --no-folding --ignore=node_modules --restow agent`. Run Pi's `/reload` and verify `/skill:poteto-mode` is available. Do not activate Guix Home just to refresh skill files.

### Orchestrate

**Coordinate a long-running, multi-track program without owning every code change.** Use this only when the work outlives one agent session and has independently verifiable units. A single change belongs in a narrower playbook.

Pi has no built-in subagent API. Use a local delegation workflow only when the active session provides one. Without delegation, this playbook can coordinate a plan and evidence, but cannot promise parallel execution or unattended progress.

## Roles

- **Coordinator.** Owns scope, dependency order, evidence, decisions, and the user-facing report. Keep durable state in the program ledger. Do not infer progress from status text alone.
- **Track owner.** Owns a bounded set of dependent units when available local delegation supports it. Reports artifacts, head SHAs, verification, and blockers.
- **Worker or verifier.** Owns a disjoint unit. Use one writer per branch or worktree. A verifier should not have authored the change.

Avoid unnecessary nesting. Every layer adds coordination overhead. Keep the active work small enough to inspect and drain.

## Run

1. Define the done predicate, scope, dependencies, owners, and verification gates.
2. Create a durable ledger using `scripts/orch/orch.ts`. Keep each unit's status and evidence explicit. The ledger records state; it does not start or monitor agents.
3. Dispatch independent units only through an available local agent workflow. Give each a standalone brief with scope, output path, verification, and report format.
4. Verify each completed artifact yourself. Missing reports or unverified claims are gaps, not passes.
5. Resolve blockers and failed units at their root. Replace stalled work only when the environment supports it and the needed artifacts are preserved.
6. Stop on the user's hold. Resume from the ledger and verify live state before continuing.
7. Close only when the done predicate is evidenced and all remaining gaps are reported.

**Reply:** the done predicate, completed units and evidence, active work, blockers, and durable ledger path. Name any limits to delegation or monitoring.

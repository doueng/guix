### Autopilot-full

**You own the verdicts, not the PRs. Each independent PR has one owner. Nothing merges without independent verification.** Use only when the operator grants full autonomy. Operator-named items remain gated for their approval.

1. **Set the done condition and gates.** State the PR order, verification requirements, merge owner, and stop conditions. Begin only with the operator's explicit go. Record the plan in the decision trail.
2. **Assign owners only through an available local delegation workflow.** Give each owner one PR, an isolated branch or worktree, a concrete scope, and a verification contract. If delegation is unavailable, stop at a sequential plan and report the limitation. Each owner records decisions and head SHAs.
3. **Keep ownership separate.** Independent PRs branch from the same base. Dependent changes wait for their prerequisite or explicitly use a private stacked base. One writer owns a branch at a time.
4. **Verify each changed head independently.** Run required checks at the exact head SHA. Exercise the real user-facing behavior, audit the diff, and record all results. Use an independent reviewer only if an available local delegation workflow supports it. Otherwise identify the review as self-review and do not treat it as an independent verdict. Findings return to the owner for a fix and fresh verification.
5. **Merge only verified heads.** Rebase onto the current target immediately before merge, rerun checks affected by the rebase, and preserve the verified patch identity. A changed patch needs fresh review. Operator-named PRs stop at merge-ready for the operator's approval.
6. **Audit progress from evidence.** If the session supports persistent monitoring, poll at a stated interval and record decisions. Otherwise do not promise unattended progress. Count commits, pushes, PR state, checks, and verified reports, not status claims. Treat a stalled worker as a gap until its state and artifacts are confirmed.
7. **Stop on request.** A hold or stand-down means no further writes until released.

**Reply:** list each PR's owner, state, and head SHA. Include verification evidence, merges, countersigns, operator gates, and decision-trail paths. State any review or delegation gaps.

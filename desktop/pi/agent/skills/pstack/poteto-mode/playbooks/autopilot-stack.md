### Autopilot-stack

**You own the stack, not the landing. Build and verify a linear stack, then hand it to the operator to review and land.** This requires full-autonomy permission for reversible work and an available delegation workflow if independent owners are needed.

1. Resolve the forge once. Prefer `gh`; use another installed forge CLI only when it can resolve this repository. Record the choice.
2. State the PR order, verification requirements, merge owner, and stop conditions. Begin only on the operator's explicit go. Keep the plan and decisions in the decision trail.
3. Use one isolated writer per PR only through an available local delegation workflow. Otherwise build sequentially. Owners work only in their assigned branches and report exact head SHAs.
4. Verify each PR at its current head. Run required checks, exercise real behavior, and independently review when possible. Record self-review as such. Findings require a fix and fresh verification.
5. Append only clean, verified changes to the linear stack. The coordinator alone changes branch topology. Rebase in order, verify changed heads, and preserve a clear dependency chain.
6. Never merge or arm auto-merge. The operator reviews and lands the stack. A stop means no further writes.

If monitoring cannot persist beyond the active Pi session, state that limitation and do not promise unattended progress.

**Reply:** links or identifiers for the stack root and tip, the verdict and head SHA for each link, and anything parked with its reason.

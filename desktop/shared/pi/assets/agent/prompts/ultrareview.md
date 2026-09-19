---
description: Deep multi-pass code review with finding, verification, and synthesis
---

Run an ultrareview for: $ARGUMENTS
If `$ARGUMENTS` is empty, auto-detect the review target.

Follow the ROACH PI `/ultrareview` workflow, adapted to the tools available in this environment.

Workflow:
1. Read `AGENTS.md` first and follow any repo-specific workflow, safety, and VCS guidance.
2. Validate the review target before doing any work. Reject it if it contains anything except letters, numbers, `.`, `-`, `_`, `/`, or `:`.
3. Resolve the review target:
   - PR number or PR URL: fetch the PR diff with `gh pr diff <target>`.
   - Branch or ref name: diff it against the repo's default integration branch using the repo's preferred VCS workflow.
   - No argument: auto-detect. First determine the current branch/ref, then check for a matching PR with `gh`. If a PR exists, review the PR diff. Otherwise review the current local change set using the repo's preferred VCS workflow.
   - In JJ repos, prefer `jj`-native diff/show commands for local changes unless a PR target specifically requires `gh pr diff`.
4. If the diff is empty, report `No changes to review` and stop.
5. Create a shared diff artifact at `docs/engineering-discipline/reviews/.tmp/<date>-<topic>.diff` so later review passes can read the same source.
6. Enumerate the touched files and then run a 3-stage review pipeline.

## Stage 1: Finding
Perform 10 independent review passes: 5 reviewer roles × 2 seeds.

Use any available read-only helper/subagent tooling to parallelize when practical. If that is not available, do the passes yourself one by one without skipping any role or seed.

Reviewer roles:
- `reviewer-bug`: logic errors, boundary cases, null/undefined handling, races, missing error handling
- `reviewer-security`: injection, auth/authz, secret handling, crypto misuse, data exposure
- `reviewer-performance`: unnecessary work, bad complexity, hot-path sync I/O, allocation churn, cache misses
- `reviewer-test-coverage`: missing tests, happy-path-only tests, uncovered edge cases, weak assertions
- `reviewer-consistency`: naming drift, convention mismatches, duplication, failure to reuse existing patterns/utilities

Seed instructions:
- Seed 1: `Perform a fresh independent pass.`
- Seed 2: `You are seed 2 — focus on findings seed 1 might miss by examining edge cases and alternative execution paths.`

For every pass:
- Read the shared diff artifact first.
- Open the touched files needed to verify behavior.
- Emit only concrete findings.
- For each finding, record: category, short label, file:line, severity (Critical/High/Medium/Low), confidence (0.7-1.0), description, and one-line fix.
- Do not emit style-only nits unless they create a real consistency or maintenance issue.

## Stage 2: Verification
Deduplicate and fact-check the raw findings.

Rules:
- Collapse duplicate findings that point to the same underlying issue.
- Open the cited files and verify that the code actually supports the claim.
- Drop findings with wrong locations, weak evidence, or clear false positives.
- Keep severity proportional to real impact.
- Raise confidence when multiple seeds/roles independently converge on the same issue.
- Do not invent new findings during verification.

Produce a verified list grouped by severity, then by file, in this format:

```markdown
## Critical

### [category] <short label>: <file>:<line>
**Confidence:** 0.X (Y reviewers agreed)
**Description:** ...
**Fix:** ...

## High
...

## Medium
...

## Low
...

## Verification Summary
- Raw findings received: N
- After dedup: M
- After verification: K
- Dropped as false positives: (N - K)
```

If no findings survive, emit only `## Verification Summary` with `K=0`.

## Stage 3: Synthesis
Write the final report to:
`docs/engineering-discipline/reviews/<date>-<topic>-review.md`

Conventions:
- `<date>` = today's date as `YYYY-MM-DD`
- `<topic>` = `pr-<number>` for PRs, otherwise a sanitized lowercase topic/ref name with `/` replaced by `-`

Use this report structure:

```markdown
# Ultrareview Report — <review target>

**Date:** <review date>
**Pipeline:** finding (10 passes) → verification → synthesis

## Summary

- **Total findings:** N
- **By severity:** Critical: a | High: b | Medium: c | Low: d
- **By category:** Bug: w | Security: x | Performance: y | Test Coverage: z | Consistency: q

## Top Priority (5 highest)

1. **[category] file:line** — severity/confidence — one-line description
2. ...
3. ...
4. ...
5. ...

## Findings

### Critical

#### [category] short-label — file:line
**Confidence:** 0.X
**Description:** ...
**Trigger / Exploit / Impact:** ...
**Fix:** ...

### High
...

### Medium
...

### Low
...

## Clean Areas

List dimensions (bug / security / performance / coverage / consistency) that produced no verified findings.

## Verification Summary
- Raw findings received: N
- After dedup: M
- After verification: K
- Dropped as false positives: (N - K)
```

Chat output requirements:
- Stream a concise summary to chat.
- Include the 5 highest-priority findings with file:line and one-line description.
- Include the saved report path.

Constraints:
- Be read-only except for writing the diff artifact and final review report.
- Do not invent findings, file paths, line numbers, or evidence.
- Prefer exact citations and concrete evidence over vague review commentary.
- If evidence is ambiguous, lower confidence or drop the finding.
- Keep the final report factual and specific; no apology, no filler.

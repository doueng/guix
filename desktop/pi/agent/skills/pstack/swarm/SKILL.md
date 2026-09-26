---
name: swarm
description: "Plan parallel coverage, independent reviews, races, or exploration partitions. Use when parallel work materially improves coverage."
disable-model-invocation: true
---

# Swarm

Plan independent work and return one evidence-based report. Use only a delegation mechanism available in the current Pi environment. If none is available, do the slices sequentially and state that they were not parallel.

## Start

1. Frame the goal and the evidence required for completion.
2. Partition work into independent slices. Name shared files or state that must not be edited concurrently.
3. Delegate only when an available local agent workflow can enforce isolated ownership. Give each worker a complete brief, exact scope, verification method, and report format.
4. Read each worker's actual output and verify the claims against the named artifacts.
5. Return one concise report with verdicts, evidence, and gaps.

For coverage, every required slice needs a result. A missing result is a gap, not a pass. Keep reviewers read-only when the work is a review. Never imply a worker ran, a test passed, or a result was independent without evidence.

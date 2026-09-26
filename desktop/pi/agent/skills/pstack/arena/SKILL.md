---
name: arena
description: "Compare competing designs or implementations when one attempt could lock in the wrong shape."
disable-model-invocation: true
---

# Arena

Compare independent candidates, select a base using explicit criteria, and verify the synthesized result. Use this workflow only when the current Pi environment has an available delegation mechanism. Otherwise state that a multi-candidate comparison is unavailable and use the design-space principle directly.

## Run

1. Name the artifact and write three to six concrete success criteria.
2. Assign each candidate an isolated output location and the same task brief. Do not allow concurrent edits to shared files.
3. Read every candidate end to end and score it against the criteria. Record missing outputs as dropouts.
4. Select the candidate that is easiest for a maintainer to extend without breaking invariants. Record the reason.
5. Graft only independently valuable ideas from other candidates, preserving one coherent design.
6. Verify the synthesized result against the same criteria and report the evidence and gaps.

Do not claim a cross-model judgment unless one actually occurred.

---
name: how
description: "Use for how a subsystem works, code walkthroughs before changes, and placement or ownership questions. Explains architecture, runtime flow, and onboarding mental models. Use why for motivation."
disable-model-invocation: true
---

# How

Explore the codebase to answer how a subsystem works. Produce an architectural explanation sufficient for a senior engineer to build a working mental model, without annotating every line.

## Step 1. Assess complexity

If the scope is ambiguous, state your interpretation and explore. The user can redirect.

- **Simple.** A single module, small utility, or narrow question. Explore and explain in one pass.
- **Complex.** A subsystem spanning files or services, or a broad architectural overview. Partition the exploration into distinct slices. If an available local agent workflow can run independently, delegate disjoint slices and synthesize their evidence. Otherwise explore the slices yourself in sequence.

When in doubt, take the simple path. Do not claim parallel exploration unless it happened.

## Step 2. Explore and explain

Trace entry points, data flow, ownership, and important boundaries. Cite the files and symbols that support the explanation. Treat separate exploration notes as evidence, not as authority.

## Step 3. Present

Present a concise explanation using the sections defined in `references/explainer-prompt.md`, dropping any that do not apply: Overview, Key Concepts, How It Works, Where Things Live, Gotchas.

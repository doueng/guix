---
description: Create an implementation plan without making changes
---

Create an implementation plan for: $ARGUMENTS

Workflow:
1. Follow `AGENTS.md` and gather the repo context needed for planning.
2. Analyze the project structure and create a concrete plan covering:
   - files that need to be created or modified
   - affected modules or features
   - impact on each target when relevant (`asahi-nix`, `openclaw`)
   - whether new configs, scripts, or packages are needed
   - validation steps (`make fmt`, `make eval`, `make build`, or narrower checks if more appropriate)
3. Call out assumptions, risks, and open questions.
4. Do **not** make any changes; this prompt is planning-only.

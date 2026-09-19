---
description: Generate a quick self-contained HTML sketch without heavy review ceremony
argument-hint: "<topic>"
---
Load the visual-explainer skill, then generate a lightweight self-contained HTML explainer for: $@

Use this when the user wants a quick architecture sketch, flow diagram, comparison, or mental model and does not need a full diff review, plan review, project recap, or slide deck.

Workflow:
1. Read only the minimum files needed to be accurate. Keep any read-only lookup narrow and focused.
2. Create one focused page with a short summary, one primary diagram or visual layout, and a concise notes section.
3. Keep data gathering bounded: do not run multi-pass review, broad history analysis, or exhaustive file reads unless the user asks.
4. Use the visual-explainer skill's CSS/layout guidance, but choose a simple aesthetic and avoid over-engineering.
5. Write the HTML to `~/.agent/diagrams/` and open it in the browser.

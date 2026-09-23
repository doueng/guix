# VENT

Feedback log. Repeated/systemic workflow friction that should become future automation, docs, or workflow fixes.

## 26-09-23 12:41 — confusing_docs

During the Guix setup review, .agents/skills/guix/references.md linked offline manuals as guix-manual.md and guix-cookbook.md beside itself, but they actually live under references/, as SKILL.md correctly states. The failed lookup required locating the files manually. Fix references.md links to references/guix-manual.md and references/guix-cookbook.md, and add a lightweight local Markdown-link check to prevent future review backtracking.

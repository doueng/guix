---
description: Explain which local Pi/JJ review workflow to use
---

Summarize the available local review workflows and recommend which one to use for: $ARGUMENTS

Use this distinction:

- `/ultrareview [target]` — expensive multi-pass review: use for high-risk changes, PRs, release-sensitive code, or when the user explicitly asks for deep review.
- `/visual-explainer-diff-review [revset]` — visual architecture/code review: use when the change is complex enough to benefit from a browser-rendered before/after explanation.

If no argument is provided, recommend the cheapest workflow that fits the current situation.

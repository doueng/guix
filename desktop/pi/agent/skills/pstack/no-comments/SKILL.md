---
name: no-comments
description: "Review a diff for comments that repeat code, preserve dead paths, or state constraints the code cannot enforce."
disable-model-invocation: true
---

# No comments

Review the caller's diff or the current working diff. Remove comments that restate what code does. Keep comments that explain a non-obvious constraint that code cannot express. Do not use comment removal as a reason to change application behavior.

## Review

1. Read each comment in context and identify the invariant or explanation it claims to preserve.
2. Remove narration, stale descriptions, and explanations that the code makes obvious.
3. Keep a constraint comment only when its claim is accurate, relevant, and not better encoded in types, tests, validation, or structure.
4. For ambiguous comments, verify the claim from the nearby code or project documentation. Do not preserve uncertainty as a comment.
5. Summarize removals, retained constraints, and any behavior changes separately. This review is read-only unless the user explicitly asks for edits.

# Pi tool workflows

- Every tool call goes through `codemode`, which runs Clojure through SCI. Its description lists the function signatures, and `(await (catalog/describe id))` returns any tool's full schema. Never guess a tool name or argument.
- Batch independent reads, searches, and diagnostics with `(await (all-settled [...]))`, then reduce the results in SCI and print only the relevant evidence and individual failures. Keep dependent calls and shared-state mutations sequential. A failed script does not undo earlier calls.
- Run programs on the host; write programs in SCI. Prefer the semantic `jj`, `guix`, `repo`, `make`, `fs`, `sys`, and `search` functions. `tools/bash` is an escape hatch for genuinely shell-specific work or host programs without a namespace. Keep parsing, filtering, grouping, sorting, joins, and aggregation in SCI; do not generate temporary Python, JavaScript, shell, or Babashka programs for them. Project-owned tests, builds, and domain-specific software remain host commands.
- Check `:truncated` before treating a result as complete. Follow `:next_cursor` until nil, and report `:value_truncated` fields as excerpts. Parse JSON with `fs/read-json`, never from `tools/read` output.
- Check command actions with `(result/check r)`. Bash returns a map with `:exit_code`, `:output`, `:truncated`, and `:full_output_path`. MCP tools return a CallToolResult; check `:isError`.
- Consider `defsession` after repeating a nontrivial pattern; do not persist one-off transforms. Keep `store` data small, such as cursors and summaries.
- Autoresearch hooks and shell scripts run outside codemode and cannot use its bindings.
